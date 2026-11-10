package com.nuvio.tv.core.locallibrary

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbRuntimeConfig
import com.nuvio.tv.data.locallibrary.LocalArtworkCacheEntity
import com.nuvio.tv.data.locallibrary.LocalArtworkDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "LocalArtworkProvider"

data class LocalArtwork(
    val posterUri: String?,
    val backdropUri: String? = posterUri,
    val tmdbId: Int? = null,
    val source: String? = null
)

data class LocalArtworkCandidate(
    val id: String,
    val source: String,
    val title: String,
    val year: String?,
    val posterUrl: String,
    val backdropUrl: String? = null,
    val tmdbId: Int? = null
)

@Singleton
class LocalArtworkProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artworkDao: LocalArtworkDao,
    private val tmdbService: TmdbService,
    private val doubanService: DoubanService,
    private val httpClient: OkHttpClient
) {
    private val artworkDirectory = File(context.filesDir, "local_artwork")
    private val posterDirectory = File(artworkDirectory, "posters")
    private val backdropDirectory = File(artworkDirectory, "backdrops")
    private val generationMutex = Mutex()
    private val remoteSemaphore = Semaphore(2)
    private val fileMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Reads only already-resolved artwork. This is intentionally cheap: it never
     * touches network, scans sibling files, or decodes a video frame.
     */
    suspend fun cachedArtwork(fileUris: List<String>): Map<String, LocalArtwork> {
        if (fileUris.isEmpty()) return emptyMap()
        val distinctUris = fileUris.distinct()
        val entries = withContext(Dispatchers.IO) {
            distinctUris.chunked(SQLITE_BIND_CHUNK).flatMap { chunk ->
                artworkDao.getByFileUris(chunk)
            }
        }
        return buildMap {
            entries.forEach { entry ->
                resolvedArtwork(entry)?.let { put(entry.fileUri, it) }
            }
        }
    }

    suspend fun cachedArtwork(fileUri: String): LocalArtwork? =
        cachedArtwork(listOf(fileUri))[fileUri]

    suspend fun searchCandidates(
        query: String,
        year: String?,
        isSeries: Boolean
    ): List<LocalArtworkCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        // A manual artwork search should immediately retry after a key or
        // network change instead of honoring the automatic-search backoff.
        TmdbRuntimeConfig.markApiAvailable()
        val mediaType = if (isSeries) "tv" else "movie"
        val tmdbCandidates = runCatching {
            tmdbService.searchArtworkCandidates(cleanQuery, year, mediaType)
        }.getOrDefault(emptyList()).mapNotNull { candidate ->
            val poster = candidate.posterUrl ?: candidate.backdropUrl
                ?: return@mapNotNull null
            LocalArtworkCandidate(
                id = "tmdb:${candidate.tmdbId}",
                source = SOURCE_TMDB,
                title = candidate.title,
                year = candidate.year,
                posterUrl = poster,
                backdropUrl = candidate.backdropUrl,
                tmdbId = candidate.tmdbId
            )
        }
        val doubanCandidates = runCatching {
            doubanService.searchArtworkCandidates(cleanQuery, isSeries)
        }.getOrDefault(emptyList()).map { candidate ->
            LocalArtworkCandidate(
                id = "douban:${candidate.subjectId}",
                source = SOURCE_DOUBAN,
                title = candidate.title,
                year = candidate.year,
                posterUrl = candidate.posterUrl
            )
        }
        (tmdbCandidates + doubanCandidates)
            .distinctBy { it.id }
            .take(MAX_CANDIDATES)
    }

    /**
     * Resolves artwork without blocking the caller's list rendering. Resolution
     * order is: persisted cache, adjacent artwork, TMDB, video-frame fallback.
     */
    suspend fun applyManualArtwork(
        fileUri: String,
        candidate: LocalArtworkCandidate
    ): LocalArtwork? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(fileUri) }.getOrNull() ?: return@withContext null
        val modifiedAt = sourceModifiedAt(uri)
        fileMutexes.getOrPut(fileUri) { Mutex() }.withLock {
            val poster = cacheRemoteImage(
                candidate.posterUrl,
                posterFile(fileUri),
                force = true
            ) ?: return@withLock null
            val backdrop = candidate.backdropUrl
                ?.takeIf { it != candidate.posterUrl }
                ?.let { url ->
                    cacheRemoteImage(
                        highResolutionBackdropUrl(url),
                        backdropFile(fileUri),
                        force = true
                    ) ?: cacheRemoteImage(url, backdropFile(fileUri), force = true)
                }
                ?: poster
            val artwork = LocalArtwork(
                posterUri = poster,
                backdropUri = backdrop,
                tmdbId = candidate.tmdbId,
                source = SOURCE_MANUAL
            )
            persist(
                fileUri = fileUri,
                artwork = artwork,
                modifiedAt = modifiedAt,
                status = STATUS_RESOLVED,
                source = SOURCE_MANUAL,
                tmdbId = candidate.tmdbId
            )
            artwork
        }
    }

    suspend fun refreshArtwork(
        fileUri: String,
        title: String,
        year: String?,
        isSeries: Boolean
    ): LocalArtwork? = withContext(Dispatchers.IO) {
        if (fileUri.isBlank()) return@withContext null
        TmdbRuntimeConfig.markApiAvailable()
        artworkDao.deleteByFileUri(fileUri)
        resolveArtwork(
            fileUri = fileUri,
            title = title,
            year = year,
            isSeries = isSeries,
            ignoreCache = true
        )
    }

    suspend fun resolveArtwork(
        fileUri: String,
        title: String,
        year: String?,
        isSeries: Boolean,
        ignoreCache: Boolean = false
    ): LocalArtwork? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(fileUri) }.getOrNull() ?: return@withContext null
        val modifiedAt = sourceModifiedAt(uri)
        val lock = fileMutexes.getOrPut(fileUri) { Mutex() }
        lock.withLock {
            val cached = if (ignoreCache) null else artworkDao.getByFileUri(fileUri)
            val sameSource = cached == null || cached.sourceModifiedAt == modifiedAt
            if (cached?.status == STATUS_RESOLVED &&
                sameSource &&
                cached.source in DISPLAYABLE_CACHE_SOURCES
            ) {
                resolvedArtwork(cached)?.let { return@withLock it }
            }
            if (cached != null && sameSource) {
                val retryAfterMs = when (cached.status) {
                    STATUS_MISSING -> MISSING_RETRY_AFTER_MS
                    STATUS_MISSING_TRANSIENT -> TRANSIENT_RETRY_AFTER_MS
                    else -> 0L
                }
                if (retryAfterMs > 0L &&
                    System.currentTimeMillis() - cached.resolvedAt < retryAfterMs
                ) {
                    return@withLock null
                }
            }

            val onlineResult = remoteSemaphore.withPermit {
                tmdbService.searchImages(
                    title = title,
                    year = year,
                    mediaType = if (isSeries) "tv" else "movie"
                )
            }
            val images = onlineResult.images
            if (images != null) {
                val posterUrl = images.posterUrl
                val poster = posterUrl?.let { url ->
                    cacheRemoteImage(url, posterFile(fileUri)) ?: url
                }
                val backdrop = when {
                    images.backdropUrl == null -> poster
                    images.backdropUrl == posterUrl -> poster
                    else -> cacheRemoteImage(
                        highResolutionBackdropUrl(images.backdropUrl),
                        backdropFile(fileUri),
                        force = ignoreCache
                    ) ?: cacheRemoteImage(
                        images.backdropUrl,
                        backdropFile(fileUri),
                        force = ignoreCache
                    )
                        ?: images.backdropUrl
                }
                if (poster != null || backdrop != null) {
                    val artwork = LocalArtwork(
                        posterUri = poster ?: backdrop,
                        backdropUri = backdrop ?: poster,
                        tmdbId = onlineResult.tmdbId,
                        source = SOURCE_TMDB
                    )
                    persist(
                        fileUri,
                        artwork,
                        modifiedAt,
                        STATUS_RESOLVED,
                        SOURCE_TMDB,
                        onlineResult.tmdbId
                    )
                    return@withLock artwork
                }
            }

            // TMDB's API host is blocked on some networks even though its image
            // CDN is reachable. Douban fills that gap before local fallbacks.
            val doubanArtwork = runCatching {
                doubanService.findArtwork(
                    title = title,
                    year = year,
                    isSeries = isSeries
                )
            }.getOrNull()
            val doubanPosterUrl = doubanArtwork?.posterUrl
            if (!doubanPosterUrl.isNullOrBlank()) {
                val poster = cacheRemoteImage(doubanPosterUrl, posterFile(fileUri))
                    ?: doubanPosterUrl
                val artwork = LocalArtwork(
                    posterUri = poster,
                    backdropUri = poster,
                    source = SOURCE_DOUBAN
                )
                persist(
                    fileUri,
                    artwork,
                    modifiedAt,
                    STATUS_RESOLVED,
                    SOURCE_DOUBAN,
                    null
                )
                return@withLock artwork
            }

            adjacentArtwork(uri)?.let { adjacent ->
                val artwork = LocalArtwork(
                    posterUri = adjacent,
                    backdropUri = adjacent,
                    source = SOURCE_SIDECAR
                )
                persist(
                    fileUri,
                    artwork,
                    modifiedAt,
                    STATUS_RESOLVED,
                    SOURCE_SIDECAR,
                    onlineResult.tmdbId
                )
                return@withLock artwork
            }

            fromExistingGeneratedFile(fileUri, modifiedAt)?.let { existing ->
                val artwork = existing.copy(source = SOURCE_FRAME)
                persist(
                    fileUri,
                    artwork,
                    modifiedAt,
                    STATUS_RESOLVED,
                    SOURCE_FRAME,
                    onlineResult.tmdbId
                )
                return@withLock artwork
            }

            // Video-frame extraction is the last resort and is serialized across
            // every local item. Some TV firmwares crash mediaserver when several
            // MediaMetadataRetriever instances decode concurrently.
            val generated = generationMutex.withLock {
                val target = posterFile(fileUri)
                if (isUsableFile(target)) {
                    target
                } else {
                    posterDirectory.mkdirs()
                    if (generatePoster(uri, target)) target else null
                }
            }
            if (generated != null) {
                val artwork = LocalArtwork(
                    posterUri = Uri.fromFile(generated).toString(),
                    backdropUri = Uri.fromFile(generated).toString(),
                    source = SOURCE_FRAME
                )
                persist(
                    fileUri,
                    artwork,
                    modifiedAt,
                    STATUS_RESOLVED,
                    SOURCE_FRAME,
                    onlineResult.tmdbId
                )
                return@withLock artwork
            }

            persist(
                fileUri,
                LocalArtwork(null, null),
                modifiedAt,
                if (onlineResult.searchCompleted) STATUS_MISSING else STATUS_MISSING_TRANSIENT,
                source = null,
                tmdbId = onlineResult.tmdbId
            )
            null
        }
    }

    private fun fromExistingGeneratedFile(
        fileUri: String,
        modifiedAt: Long
    ): LocalArtwork? {
        val poster = posterFile(fileUri)
        if (!isUsableFile(poster)) return null
        if (modifiedAt > 0L && poster.lastModified() < modifiedAt) return null
        val artwork = LocalArtwork(
            posterUri = Uri.fromFile(poster).toString(),
            backdropUri = Uri.fromFile(poster).toString()
        )
        return artwork
    }

    private suspend fun persist(
        fileUri: String,
        artwork: LocalArtwork,
        modifiedAt: Long,
        status: String,
        source: String?,
        tmdbId: Int?
    ) {
        artworkDao.upsert(
            LocalArtworkCacheEntity(
                fileUri = fileUri,
                posterUri = artwork.posterUri,
                backdropUri = artwork.backdropUri,
                sourceModifiedAt = modifiedAt,
                status = status,
                source = source,
                tmdbId = tmdbId,
                resolvedAt = System.currentTimeMillis()
            )
        )
    }

    private fun resolvedArtwork(entry: LocalArtworkCacheEntity): LocalArtwork? {
        if (entry.status != STATUS_RESOLVED || entry.source !in DISPLAYABLE_CACHE_SOURCES) return null
        val poster = usableArtworkUri(entry.posterUri)
        val backdrop = usableArtworkUri(entry.backdropUri)
        if (poster == null && backdrop == null) return null
        return LocalArtwork(
            posterUri = poster ?: backdrop,
            backdropUri = backdrop ?: poster,
            tmdbId = entry.tmdbId,
            source = entry.source
        )
    }

    private fun usableArtworkUri(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (uri.scheme != ContentResolver.SCHEME_FILE) return value
        return uri.path?.let(::File)?.takeIf(::isUsableFile)?.let(Uri::fromFile)?.toString()
    }

    private fun adjacentArtwork(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return null
        val video = uri.path?.let(::File)?.takeIf { it.isFile } ?: return null
        val siblings = video.parentFile?.listFiles()?.associateBy { it.name.lowercase() }
            ?: return null
        val baseName = video.nameWithoutExtension
        val candidates = listOf(
            "$baseName.jpg",
            "$baseName.jpeg",
            "$baseName.png",
            "$baseName.webp",
            "$baseName-poster.jpg",
            "$baseName-poster.png",
            "poster.jpg",
            "poster.png",
            "folder.jpg",
            "folder.png",
            "cover.jpg",
            "cover.png"
        )
        return candidates.firstNotNullOfOrNull { name ->
            siblings[name.lowercase()]
                ?.takeIf(::isUsableFile)
                ?.let { Uri.fromFile(it).toString() }
        }
    }

    private fun posterFile(fileUri: String): File = File(
        posterDirectory,
        "${UUID.nameUUIDFromBytes(fileUri.toByteArray())}.jpg"
    )

    private fun backdropFile(fileUri: String): File = File(
        backdropDirectory,
        "${UUID.nameUUIDFromBytes(fileUri.toByteArray())}.jpg"
    )

    private suspend fun cacheRemoteImage(
        url: String,
        target: File,
        force: Boolean = false
    ): String? =
        withContext(Dispatchers.IO) {
            if (!force && isUsableFile(target)) {
                return@withContext Uri.fromFile(target).toString()
            }
            val directory = target.parentFile ?: return@withContext null
            directory.mkdirs()
            val temporary = File(directory, "${target.name}.tmp")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 9; Android TV) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                    )
                    .apply {
                        if (url.contains("douban", ignoreCase = true)) {
                            header("Referer", "https://movie.douban.com/")
                        }
                    }
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body
                    if (body.contentLength() > MAX_DOWNLOAD_BYTES) return@withContext null
                    body.byteStream().use { input ->
                        FileOutputStream(temporary).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                if (temporary.length() <= 0L || temporary.length() > MAX_DOWNLOAD_BYTES) {
                    temporary.delete()
                    return@withContext null
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                Uri.fromFile(target).toString()
            } catch (error: CancellationException) {
                temporary.delete()
                throw error
            } catch (error: Exception) {
                temporary.delete()
                Log.w(TAG, "Could not cache artwork $url: ${error.message}")
                null
            }
        }

    private fun highResolutionBackdropUrl(url: String): String =
        url
            .replace("/t/p/w1280/", "/t/p/original/")
            .replace("/t/p/w780/", "/t/p/original/")
            .replace("/t/p/w500/", "/t/p/original/")

    private fun sourceModifiedAt(uri: Uri): Long {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return 0L
        return uri.path?.let(::File)?.lastModified() ?: 0L
    }

    private fun isUsableFile(file: File): Boolean =
        file.isFile && file.length() > 0L

    private fun generatePoster(uri: Uri, target: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val path = uri.path ?: return false
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val frameTimeUs = if (durationMs > 0L) {
                (durationMs * 1_000L / 10L).coerceAtLeast(1_000_000L)
            } else {
                1_000_000L
            }
            val frame = retrieveFrame(retriever, frameTimeUs)
                ?: retrieveFrame(retriever, 0L)
                ?: return false
            val poster = scaleDown(frame)
            val written = runCatching {
                FileOutputStream(target).use { output ->
                    poster.compress(Bitmap.CompressFormat.JPEG, 86, output)
                }
            }.getOrDefault(false)
            if (poster !== frame) poster.recycle()
            frame.recycle()
            written
        } catch (error: Exception) {
            Log.w(TAG, "Frame extraction failed: ${error.message}")
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun retrieveFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                POSTER_MAX_WIDTH,
                POSTER_MAX_HEIGHT
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= POSTER_MAX_WIDTH && bitmap.height <= POSTER_MAX_HEIGHT) return bitmap
        val scale = minOf(
            POSTER_MAX_WIDTH.toFloat() / bitmap.width,
            POSTER_MAX_HEIGHT.toFloat() / bitmap.height
        )
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val POSTER_MAX_WIDTH = 960
        private const val POSTER_MAX_HEIGHT = 540
        private const val MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L
        private const val SQLITE_BIND_CHUNK = 400
        private const val STATUS_RESOLVED = "RESOLVED"
        private const val STATUS_MISSING = "MISSING"
        private const val STATUS_MISSING_TRANSIENT = "MISSING_TRANSIENT"
        private const val SOURCE_TMDB = "TMDB"
        private const val SOURCE_DOUBAN = "DOUBAN"
        private const val SOURCE_SIDECAR = "SIDECAR"
        private const val SOURCE_FRAME = "FRAME"
        private const val SOURCE_MANUAL = "MANUAL"
        private val DISPLAYABLE_CACHE_SOURCES = setOf(
            SOURCE_TMDB,
            SOURCE_DOUBAN,
            SOURCE_SIDECAR,
            SOURCE_MANUAL
        )
        private const val MAX_CANDIDATES = 20
        private const val MISSING_RETRY_AFTER_MS = 24L * 60L * 60L * 1000L
        private const val TRANSIENT_RETRY_AFTER_MS = 6L * 60L * 60L * 1000L
    }
}
