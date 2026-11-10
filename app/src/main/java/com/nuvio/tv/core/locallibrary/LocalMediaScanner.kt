package com.nuvio.tv.core.locallibrary

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nuvio.tv.R
import com.nuvio.tv.data.locallibrary.LocalMediaItemDao
import com.nuvio.tv.data.locallibrary.LocalMediaItemEntity
import com.nuvio.tv.data.locallibrary.LocalSourceDao
import com.nuvio.tv.data.locallibrary.LocalSourceEntity
import com.nuvio.tv.data.locallibrary.LocalSourceType
import com.nuvio.tv.core.smb.SmbConnectionManager
import com.nuvio.tv.core.smb.SmbUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalScanResult(
    val itemCount: Int,
    val error: String? = null
)

@Singleton
class LocalMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: LocalSourceDao,
    private val itemDao: LocalMediaItemDao,
    private val smbConnectionManager: SmbConnectionManager
) {
    suspend fun scan(source: LocalSourceEntity): LocalScanResult = withContext(Dispatchers.IO) {
        val sourceUri = runCatching { Uri.parse(source.treeUri) }.getOrNull()
        val isFileTree = sourceUri?.scheme == ContentResolver.SCHEME_FILE
        val files = when {
            source.type == LocalSourceType.SMB -> {
                val target = SmbUri.parse(source.treeUri)
                if (target == null) {
                    val error = context.getString(R.string.local_media_source_unavailable)
                    sourceDao.updateScan(
                        source.id,
                        System.currentTimeMillis(),
                        source.itemCount,
                        error,
                        false
                    )
                    return@withContext LocalScanResult(0, error)
                }
                runCatching { listSmbVideos(source, target) }.getOrElse { error ->
                    val message = context.getString(
                        R.string.local_media_scan_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                    sourceDao.updateScan(
                        source.id,
                        System.currentTimeMillis(),
                        source.itemCount,
                        message,
                        false
                    )
                    return@withContext LocalScanResult(0, message)
                }
            }
            isFileTree -> {
                val root = sourceUri?.path?.let(::File)
                if (root == null || !root.isDirectory) {
                    val error = context.getString(R.string.local_media_source_unavailable)
                    sourceDao.updateScan(
                        source.id,
                        System.currentTimeMillis(),
                        source.itemCount,
                        error,
                        false
                    )
                    return@withContext LocalScanResult(0, error)
                }
                runCatching { listFileVideos(root) }.getOrElse { error ->
                    val message = context.getString(
                        R.string.local_media_scan_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                    sourceDao.updateScan(
                        source.id,
                        System.currentTimeMillis(),
                        source.itemCount,
                        message,
                        false
                    )
                    return@withContext LocalScanResult(0, message)
                }
            }
            else -> {
                val root = runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(source.treeUri))
                }.getOrNull()
                if (root == null || !root.isDirectory) {
                    val error = context.getString(R.string.local_media_source_unavailable)
                    sourceDao.updateScan(
                        source.id,
                        System.currentTimeMillis(),
                        source.itemCount,
                        error,
                        false
                    )
                    return@withContext LocalScanResult(0, error)
                }
                runCatching { listDocumentVideos(root) }.getOrElse { error ->
                    val message = context.getString(
                        R.string.local_media_scan_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                    sourceDao.updateScan(
                        source.id,
                        System.currentTimeMillis(),
                        source.itemCount,
                        message,
                        false
                    )
                    return@withContext LocalScanResult(0, message)
                }
            }
        }
        val scannedAt = System.currentTimeMillis()
        val existingAddedAt = itemDao.getBySource(source.id)
            .associate { it.id to it.addedAt }
        val items = files.map { file ->
            val parsed = runCatching { FilenameTitleParser.parse(file.name) }.getOrElse {
                FilenameTitleParser.ParsedName(
                    title = file.name.substringBeforeLast('.').trim().ifBlank { file.name },
                    year = null,
                    kind = com.nuvio.tv.data.locallibrary.LocalMediaKind.MOVIE,
                    season = null,
                    episode = null,
                    groupKey = null
                )
            }
            val itemId = stableId(source.id, file.uri)
            LocalMediaItemEntity(
                id = itemId,
                sourceId = source.id,
                fileUri = file.uri,
                fileName = file.name,
                kind = parsed.kind,
                title = parsed.title,
                year = parsed.year,
                season = parsed.season,
                episode = parsed.episode,
                groupKey = parsed.groupKey,
                fileSizeBytes = file.size,
                // Preserve the first time this file entered the library. A rescan must not
                // reorder "recently added" rows; only genuinely new files get the scan time.
                addedAt = existingAddedAt[itemId] ?: scannedAt
            )
        }
        itemDao.replaceForSource(source.id, items)
        sourceDao.updateScan(source.id, scannedAt, items.size, null, true)
        LocalScanResult(items.size)
    }

    suspend fun isAvailable(source: LocalSourceEntity): Boolean = withContext(Dispatchers.IO) {
        if (source.type == LocalSourceType.SMB) {
            val target = SmbUri.parse(source.treeUri) ?: return@withContext false
            return@withContext runCatching {
                smbConnectionManager.list(source, target.path)
            }.isSuccess
        }
        val sourceUri = runCatching { Uri.parse(source.treeUri) }.getOrNull()
            ?: return@withContext false
        if (sourceUri.scheme == ContentResolver.SCHEME_FILE) {
            val root = sourceUri.path?.let(::File) ?: return@withContext false
            root.isDirectory && root.canRead() &&
                runCatching { root.listFiles() }.getOrNull() != null
        } else {
            val root = runCatching { DocumentFile.fromTreeUri(context, sourceUri) }.getOrNull()
            root?.isDirectory == true && runCatching { root.listFiles() }.getOrNull() != null
        }
    }

    private fun listDocumentVideos(root: DocumentFile): List<ScannedVideoFile> {
        val result = ArrayList<ScannedVideoFile>()
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED) {
            val directory = queue.removeFirst()
            val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
            for (child in children) {
                if (visited++ >= MAX_VISITED) break
                val name = child.name.orEmpty()
                if (child.isDirectory) {
                    if (!name.startsWith(".")) queue.addLast(child)
                } else if (isScannableVideo(name, child.length())) {
                    result.add(
                        ScannedVideoFile(
                            name = name,
                            uri = child.uri.toString(),
                            size = child.length()
                        )
                    )
                }
            }
        }
        return result
    }

    private suspend fun listSmbVideos(
        source: LocalSourceEntity,
        root: com.nuvio.tv.core.smb.SmbTarget
    ): List<ScannedVideoFile> {
        val result = ArrayList<ScannedVideoFile>()
        val queue = ArrayDeque<String>()
        queue.add(root.path)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED) {
            val directory = queue.removeFirst()
            val children = smbConnectionManager.list(source, directory)
            for (child in children) {
                if (visited++ >= MAX_VISITED) break
                if (child.isDirectory) {
                    if (!shouldSkipSmbDirectory(child.name)) queue.addLast(child.path)
                } else if (isScannableVideo(child.name, child.sizeBytes)) {
                    result.add(
                        ScannedVideoFile(
                            name = child.name,
                            uri = root.uriFor(child.path),
                            size = child.sizeBytes
                        )
                    )
                }
            }
        }
        return result
    }

    private fun shouldSkipSmbDirectory(name: String): Boolean {
        if (name.startsWith(".")) return true
        return name in SMB_SKIPPED_DIRECTORIES
    }

    private fun listFileVideos(root: File): List<ScannedVideoFile> {
        val result = ArrayList<ScannedVideoFile>()
        val queue = ArrayDeque<File>()
        val visitedDirectories = HashSet<String>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED) {
            val directory = queue.removeFirst()
            val directoryKey = runCatching { directory.canonicalPath }
                .getOrElse { directory.absolutePath }
            if (!visitedDirectories.add(directoryKey)) continue
            val children = directory.listFiles()
            if (children == null) {
                if (directory == root) {
                    throw SecurityException("Cannot read ${root.absolutePath}")
                }
                continue
            }
            for (child in children) {
                if (visited++ >= MAX_VISITED) break
                val name = child.name
                if (child.isDirectory) {
                    if (!name.startsWith(".")) queue.addLast(child)
                } else if (isScannableVideo(name, runCatching { child.length() }.getOrDefault(0L))) {
                    result.add(
                        ScannedVideoFile(
                            name = name,
                            uri = Uri.fromFile(child).toString(),
                            size = runCatching { child.length() }.getOrDefault(0L)
                        )
                    )
                }
            }
        }
        return result
    }

    private fun isScannableVideo(name: String, size: Long): Boolean {
        // macOS creates AppleDouble sidecars named "._movie.mkv" that are only a few KB.
        // They share the video extension but are metadata, not playable media.
        if (name.startsWith(".")) return false
        if (name.substringAfterLast('.', "").lowercase() !in VIDEO_EXTENSIONS) return false
        return size <= 0L || size >= MIN_VIDEO_BYTES
    }

    private data class ScannedVideoFile(
        val name: String,
        val uri: String,
        val size: Long
    )

    companion object {
        private const val MAX_VISITED = 200_000
        private const val MIN_VIDEO_BYTES = 1L * 1024L * 1024L
        private val SMB_SKIPPED_DIRECTORIES = setOf(
            "\$RECYCLE.BIN",
            "System Volume Information",
            "@eaDir",
            "#recycle"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "ts", "m2ts",
            "mts", "vob", "mpg", "mpeg", "3gp", "rm", "rmvb", "iso"
        )

        fun stableId(sourceId: Long, fileUri: String): String =
            "$sourceId:${UUID.nameUUIDFromBytes(fileUri.toByteArray())}"
    }
}

