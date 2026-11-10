package com.nuvio.tv.data.locallibrary

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.locallibrary.LocalArtwork
import com.nuvio.tv.core.locallibrary.LocalArtworkProvider
import com.nuvio.tv.core.locallibrary.FilenameTitleParser
import com.nuvio.tv.core.locallibrary.DoubanService
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbArtworkCandidate
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

private const val MIN_VISIBLE_FILE_BYTES = 1L * 1024L * 1024L

data class LocalArtworkUpdate(
    val previewId: String,
    val artwork: LocalArtwork,
    val enrichment: TmdbEnrichment? = null
)

data class LocalSearchResult(
    val movies: List<MetaPreview> = emptyList(),
    val series: List<MetaPreview> = emptyList()
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
}

private data class ArtworkTarget(
    val previewId: String,
    val fileUri: String,
    val title: String,
    val year: String?,
    val isSeries: Boolean,
    val indexFileUris: List<String> = listOf(fileUri)
)

private data class LocalMetadataTarget(
    val items: List<LocalMediaItemEntity>,
    val previewId: String,
    val fileUri: String,
    val title: String,
    val year: String?,
    val isSeries: Boolean
) {
    val indexFileUris: List<String> get() = items.map { it.fileUri }.distinct()

    fun toArtworkTarget(): ArtworkTarget = ArtworkTarget(
        previewId = previewId,
        fileUri = fileUri,
        title = title,
        year = year,
        isSeries = isSeries,
        indexFileUris = indexFileUris
    )
}

@Singleton
class LocalCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LocalLibraryRepository,
    private val artworkProvider: LocalArtworkProvider,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val doubanService: DoubanService
) {
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchSemaphore = Semaphore(4)
    private val searchIndexSemaphore = Semaphore(2)
    private val prefetchJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var searchIndexWarmupJob: Job? = null
    private val searchIndexAttempted = ConcurrentHashMap.newKeySet<String>()
    private val searchIndexInFlight = ConcurrentHashMap.newKeySet<String>()
    private val _artworkUpdates = MutableSharedFlow<LocalArtworkUpdate>(
        replay = ARTWORK_REPLAY_CACHE,
        extraBufferCapacity = ARTWORK_REPLAY_CACHE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val artworkUpdates: SharedFlow<LocalArtworkUpdate> = _artworkUpdates.asSharedFlow()

    fun notifyArtworkUpdated(
        previewId: String,
        artwork: LocalArtwork,
        enrichment: TmdbEnrichment? = null
    ) {
        if (previewId.isBlank()) return
        _artworkUpdates.tryEmit(LocalArtworkUpdate(previewId, artwork, enrichment))
    }

    val addons: Flow<List<Addon>> = combine(
        repository.observeEnabledSources(),
        repository.observeSourceKindCounts()
    ) { sources, counts ->
        val enabledIds = sources.mapTo(HashSet()) { it.id }
        val visibleCounts = counts.filter { it.sourceId in enabledIds && it.itemCount > 0 }
        val kinds = visibleCounts.mapTo(HashSet()) { it.kind }
        if (kinds.isEmpty()) {
            emptyList()
        } else {
            listOf(
                aggregateAddon(
                    kinds = kinds,
                    itemCount = visibleCounts.sumOf { it.itemCount }
                )
            )
        }
    }.distinctUntilChanged()

    val sourceCount: Flow<Int> = repository.observeSources()
        .map { it.size }
        .distinctUntilChanged()

    suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        catalogId: String,
        catalogName: String,
        type: String
    ): CatalogRow? {
        val singleSourceId = sourceId(addonBaseUrl)
        val sources = if (singleSourceId != null) {
            listOfNotNull(repository.getSource(singleSourceId))
        } else {
            repository.getAllSources()
        }
        if (sources.isEmpty()) return null
        val sourceNames = sources.associate { it.id to it.name }
        val isSeries = type == ContentType.SERIES.toApiString()
        val isRecent = catalogId == CATALOG_RECENT_MOVIES || catalogId == CATALOG_RECENT_SERIES
        val items = previews(
            type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
            sources = sources,
            sourceNames = sourceNames,
            recentOnly = isRecent,
            limit = if (isRecent) RECENT_ROW_LIMIT else null
        )
        return CatalogRow(
            addonId = addonId,
            addonName = if (singleSourceId != null) {
                sources.firstOrNull()?.let(::displayName) ?: aggregateDisplayName()
            } else {
                aggregateDisplayName()
            },
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
            rawType = type,
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false,
            nextSkip = 0
        )
    }

    suspend fun getMovie(itemId: String): LocalMediaItemEntity? {
        val rawId = itemId.removePrefix(MOVIE_PREFIX)
        return repository.getItem(rawId)
    }

    suspend fun getSeries(sourceId: Long, groupKey: String): List<LocalMediaItemEntity> =
        if (sourceId == ALL_SOURCES) {
            repository.getSeriesEpisodes(groupKey)
        } else {
            repository.getSeriesEpisodes(sourceId, groupKey)
        }

    suspend fun getDeletableLocalItems(contentId: String): List<LocalMediaItemEntity> =
        repository.filterDeletableItems(localItems(contentId))

    suspend fun deleteLocalMedia(contentId: String): LocalMediaDeleteResult =
        repository.deleteItems(getDeletableLocalItems(contentId))

    private suspend fun localItems(contentId: String): List<LocalMediaItemEntity> {
        if (isMovieItemId(contentId)) {
            return listOfNotNull(getMovie(contentId))
        }
        val ref = seriesRef(contentId) ?: return emptyList()
        return getSeries(ref.first, ref.second)
    }

    /**
     * Resolves cached artwork for a local content id used by watch history.
     * Movies map directly to their primary file. Series use the same first
     * episode that the local detail screen uses for its artwork.
     */
    suspend fun cachedArtworkForContentId(contentId: String?, videoId: String? = null): LocalArtwork? {
        val id = contentId?.takeIf { isLocalItemId(it) } ?: return null
        val fileUri = if (isMovieItemId(id)) {
            getMovie(id)?.fileUri
        } else {
            val ref = seriesRef(id) ?: return null
            val episodes = getSeries(ref.first, ref.second)
            (episodes.firstOrNull { it.id == videoId } ?: episodes.firstOrNull())?.fileUri
        } ?: return null
        return artworkProvider.cachedArtwork(fileUri)
    }

    /**
     * True when the local item behind [contentId] still exists in the library.
     * Used to drop stale Continue Watching entries after a folder is removed.
     */
    suspend fun isContentAvailable(contentId: String?, videoId: String? = null): Boolean {
        val id = contentId?.takeIf { isLocalItemId(it) } ?: return true
        if (isMovieItemId(id)) return getMovie(id) != null

        val ref = seriesRef(id) ?: return false
        val episodes = getSeries(ref.first, ref.second)
        return if (videoId.isNullOrBlank()) {
            episodes.isNotEmpty()
        } else {
            episodes.any { it.id == videoId }
        }
    }

    suspend fun sources(): List<LocalSourceEntity> = repository.getAllSources()

    suspend fun allSources(): List<LocalSourceEntity> =
        repository.getAllSourcesIncludingUnavailable()

    suspend fun refreshAvailability(force: Boolean = false): Boolean =
        repository.refreshAvailability(force)

    suspend fun hasAvailableSources(): Boolean = repository.hasAvailableSources()

    suspend fun previews(
        type: ContentType,
        recentOnly: Boolean = false,
        limit: Int? = null
    ): List<MetaPreview> {
        val sources = repository.getAllSources()
        if (sources.isEmpty()) return emptyList()
        return previews(
            type = type,
            sources = sources,
            sourceNames = sources.associate { it.id to it.name },
            recentOnly = recentOnly,
            limit = limit
        )
    }

    suspend fun search(query: String, limit: Int = 60): LocalSearchResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return LocalSearchResult()
        val sources = repository.getAllSources()
        if (sources.isEmpty()) return LocalSearchResult()
        val sourceNames = sources.associate { it.id to it.name }
        warmSearchIndexInBackground(sources)
        val directMatches = repository.searchItems(cleanQuery, limit)
        val matches = if (directMatches.isEmpty() && cleanQuery.length >= METADATA_SEARCH_MIN_QUERY_LENGTH) {
            findMetadataSearchMatches(cleanQuery, sources).ifEmpty { directMatches }
        } else {
            directMatches
        }
            .filter { it.sourceId in sourceNames && it.isDisplayableLibraryItem() }
        return LocalSearchResult(
            movies = moviePreviews(
                items = matches.filter { it.kind == LocalMediaKind.MOVIE },
                sources = sources,
                sourceNames = sourceNames,
                recentOnly = false,
                limit = limit
            ),
            series = seriesPreviews(
                items = matches.filter { it.kind == LocalMediaKind.SERIES_EPISODE },
                sources = sources,
                sourceNames = sourceNames,
                recentOnly = false,
                limit = limit
            )
        )
    }

    suspend fun cacheSearchMetadata(contentId: String, enrichment: TmdbEnrichment) {
        val fileUris = when {
            isMovieItemId(contentId) -> listOfNotNull(getMovie(contentId)?.fileUri)
            else -> seriesRef(contentId)
                ?.let { (sourceId, groupKey) -> getSeries(sourceId, groupKey).map { it.fileUri } }
                .orEmpty()
        }
        repository.upsertSearchIndex(fileUris, enrichment.toLocalSearchText())
    }

    private suspend fun cacheSearchMetadata(
        target: ArtworkTarget,
        tmdbId: Int?
    ): TmdbEnrichment? {
        if (repository.hasAnySearchIndex(target.indexFileUris)) return null
        val settings = tmdbSettingsDataStore.settings.value
        val contentType = if (target.isSeries) ContentType.SERIES else ContentType.MOVIE
        val resolvedTmdbId = tmdbId ?: runCatching {
            tmdbService.searchImages(
                title = target.title,
                year = target.year,
                mediaType = if (target.isSeries) "tv" else "movie"
            ).tmdbId
        }.getOrNull()
        val tmdbEnrichment = resolvedTmdbId?.let { id ->
            runCatching {
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = id.toString(),
                    contentType = contentType,
                    language = settings.language
                )
            }.getOrNull()
        }
        val enrichment = tmdbEnrichment ?: if (settings.useDouban) {
            runCatching {
                doubanService.fetchDetails(
                    title = target.title,
                    year = target.year,
                    isSeries = target.isSeries
                )?.toTmdbEnrichment()
            }.getOrNull()
        } else {
            null
        }
        enrichment?.let {
            repository.upsertSearchIndex(target.indexFileUris, it.toLocalSearchText())
        }
        return enrichment
    }

    private suspend fun findMetadataSearchMatches(
        query: String,
        sources: List<LocalSourceEntity>
    ): List<LocalMediaItemEntity> = withTimeoutOrNull(METADATA_SEARCH_TIMEOUT_MS) {
        val candidates = coroutineScope {
            val movies = async {
                runCatching {
                    tmdbService.searchArtworkCandidates(query, year = null, mediaType = "movie")
                }.getOrDefault(emptyList())
            }
            val series = async {
                runCatching {
                    tmdbService.searchArtworkCandidates(query, year = null, mediaType = "tv")
                }.getOrDefault(emptyList())
            }
            movies.await().map { it to false } + series.await().map { it to true }
        }
        if (candidates.isEmpty()) return@withTimeoutOrNull emptyList()

        val targets = buildLocalMetadataTargets(sources)
        if (targets.isEmpty()) return@withTimeoutOrNull emptyList()

        val artworkByFile = artworkProvider.cachedArtwork(
            targets.flatMap { it.indexFileUris }.distinct()
        )
        val matches = targets.mapNotNull { target ->
            val cachedTmdbId = target.indexFileUris
                .asSequence()
                .mapNotNull { artworkByFile[it]?.tmdbId }
                .firstOrNull()
            val candidate = candidates.firstOrNull { (candidate, isSeries) ->
                isSeries == target.isSeries &&
                    (
                        (cachedTmdbId != null && candidate.tmdbId == cachedTmdbId) ||
                            matchesLocalSearchTitle(
                                localTitle = target.title,
                                localYear = target.year,
                                candidateTitle = candidate.title,
                                candidateOriginalTitle = candidate.originalTitle,
                                candidateYear = candidate.year
                            )
                        )
            }?.first ?: return@mapNotNull null
            target to candidate
        }

        matches.forEach { (target, candidate) ->
            scheduleSearchIndex(target.toArtworkTarget(), candidate.tmdbId)
        }
        matches.flatMap { (target, _) -> target.items }
    } ?: emptyList()

    private fun warmSearchIndexInBackground(sources: List<LocalSourceEntity>) {
        if (sources.isEmpty() || searchIndexWarmupJob?.isActive == true) return
        searchIndexWarmupJob = artworkScope.launch {
            try {
                val targets = buildLocalMetadataTargets(sources)
                if (targets.isEmpty()) return@launch
                val indexed = repository.getIndexedSearchFileUris().toMutableSet()
                val artworkByFile = artworkProvider.cachedArtwork(
                    targets.flatMap { it.indexFileUris }.distinct()
                )
                targets.forEach { target ->
                    if (target.indexFileUris.any { it in indexed }) return@forEach
                    if (!searchIndexAttempted.add(target.previewId)) return@forEach
                    if (!searchIndexInFlight.add(target.previewId)) return@forEach
                    try {
                        searchIndexSemaphore.withPermit {
                            val cachedTmdbId = target.indexFileUris
                                .asSequence()
                                .mapNotNull { artworkByFile[it]?.tmdbId }
                                .firstOrNull()
                            val enrichment = cacheSearchMetadata(
                                target.toArtworkTarget(),
                                cachedTmdbId
                            )
                            if (enrichment != null) indexed.addAll(target.indexFileUris)
                        }
                    } finally {
                        searchIndexInFlight.remove(target.previewId)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not warm local search metadata index", error)
            }
        }
    }

    private fun scheduleSearchIndex(target: ArtworkTarget, tmdbId: Int?) {
        if (!searchIndexInFlight.add(target.previewId)) return
        artworkScope.launch {
            try {
                searchIndexSemaphore.withPermit {
                    cacheSearchMetadata(target, tmdbId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not index local search metadata for ${target.fileUri}", error)
            } finally {
                searchIndexInFlight.remove(target.previewId)
            }
        }
    }

    private suspend fun buildLocalMetadataTargets(
        sources: List<LocalSourceEntity>
    ): List<LocalMetadataTarget> {
        val sourceIds = sources.mapTo(HashSet()) { it.id }
        val movies = repository.getMovies()
            .filter { it.sourceId in sourceIds && it.isDisplayableLibraryItem() }
        val movieTargets = movies
            .groupBy { movieGroupKey(it) }
            .values
            .mapNotNull { group ->
                val primary = group.maxWithOrNull(MOVIE_PRIMARY_COMPARATOR)
                    ?: return@mapNotNull null
                val parsed = runCatching { FilenameTitleParser.parse(primary.fileName) }
                    .getOrNull()
                LocalMetadataTarget(
                    items = group,
                    previewId = MOVIE_PREFIX + primary.id,
                    fileUri = primary.fileUri,
                    title = parsed?.title?.takeIf { it.isNotBlank() } ?: primary.title,
                    year = primary.year?.takeIf { it.isNotBlank() } ?: parsed?.year,
                    isSeries = false
                )
            }

        val episodes = repository.getSeriesEpisodes()
            .filter { it.sourceId in sourceIds && it.isDisplayableLibraryItem() }
        val seriesTargets = episodes
            .groupBy { it.groupKey ?: it.title.lowercase(Locale.ROOT) }
            .values
            .mapNotNull { group ->
                val ordered = group.sortedWith(EPISODE_COMPARATOR)
                val first = ordered.firstOrNull() ?: return@mapNotNull null
                val parsed = runCatching { FilenameTitleParser.parse(first.fileName) }
                    .getOrNull()
                val groupKey = first.groupKey ?: first.title.lowercase(Locale.ROOT)
                val groupSourceIds = ordered.map { it.sourceId }.distinct()
                LocalMetadataTarget(
                    items = ordered,
                    previewId = seriesId(groupSourceIds, groupKey),
                    fileUri = first.fileUri,
                    title = parsed?.title?.takeIf { it.isNotBlank() } ?: first.title,
                    year = first.year?.takeIf { it.isNotBlank() } ?: parsed?.year,
                    isSeries = true
                )
            }

        return movieTargets + seriesTargets
    }

    private suspend fun previews(
        type: ContentType,
        sources: List<LocalSourceEntity>,
        sourceNames: Map<Long, String>,
        recentOnly: Boolean,
        limit: Int?
    ): List<MetaPreview> {
        val sourceIds = sources.mapTo(HashSet()) { it.id }
        val items = when (type) {
            ContentType.SERIES -> repository.getSeriesEpisodes()
            else -> repository.getMovies()
        }.filter { it.sourceId in sourceIds && it.isDisplayableLibraryItem() }
        return if (type == ContentType.SERIES) {
            seriesPreviews(items, sources, sourceNames, recentOnly, limit)
        } else {
            moviePreviews(items, sources, sourceNames, recentOnly, limit)
        }
    }

    private suspend fun moviePreviews(
        items: List<LocalMediaItemEntity>,
        sources: List<LocalSourceEntity>,
        sourceNames: Map<Long, String>,
        recentOnly: Boolean,
        limit: Int?
    ): List<MetaPreview> {
        val groups = items
            .filter { it.isDisplayableLibraryItem() }
            .groupBy { movieGroupKey(it) }
            .values
            .map { group -> group to group.maxWithOrNull(MOVIE_PRIMARY_COMPARATOR) }
            .filter { (_, primary) -> primary != null }
            .map { (group, primary) -> group to primary!! }

        val previewItems = groups.map { (group, primary) ->
            val parsed = runCatching { FilenameTitleParser.parse(primary.fileName) }.getOrNull()
            val sourceIds = group.map { it.sourceId }.distinct()
            val artwork = artworkProvider.cachedArtwork(primary.fileUri)
            Triple(
                MetaPreview(
                    id = MOVIE_PREFIX + primary.id,
                    type = ContentType.MOVIE,
                    name = parsed?.title?.takeIf { it.isNotBlank() } ?: primary.title,
                    poster = artwork?.posterUri,
                    posterShape = PosterShape.POSTER,
                    background = artwork?.backdropUri,
                    logo = null,
                    description = null,
                    releaseInfo = primary.year?.takeIf { it.isNotBlank() } ?: parsed?.year,
                    imdbRating = null,
                    genres = emptyList(),
                    sourceAddonBaseUrl = singleOrAggregateBaseUrl(sourceIds),
                    localSourceName = localSourceLabel(sourceIds, sourceNames),
                    localSourceIds = sourceIds
                ),
                primary,
                parsed
            )
        }

        val sorted = if (recentOnly) {
            previewItems.sortedByDescending { (_, primary, _) -> primary.addedAt }
        } else {
            previewItems.sortedBy { (preview, _, _) -> preview.name.lowercase(Locale.ROOT) }
        }
        val limited = limit?.let(sorted::take) ?: sorted
        startArtworkPrefetch(
            limited.map { (preview, primary, parsed) ->
                ArtworkTarget(
                    previewId = preview.id,
                    fileUri = primary.fileUri,
                    title = parsed?.title?.takeIf { it.isNotBlank() } ?: primary.title,
                    year = primary.year?.takeIf { it.isNotBlank() } ?: parsed?.year,
                    isSeries = false,
                    indexFileUris = items
                        .filter { movieGroupKey(it) == movieGroupKey(primary) }
                        .map { it.fileUri }
                        .distinct()
                )
            }
        )
        return limited.map { it.first }
    }

    private suspend fun seriesPreviews(
        items: List<LocalMediaItemEntity>,
        sources: List<LocalSourceEntity>,
        sourceNames: Map<Long, String>,
        recentOnly: Boolean,
        limit: Int?
    ): List<MetaPreview> {
        val groups = items
            .filter { it.isDisplayableLibraryItem() }
            .groupBy { it.groupKey ?: it.title.lowercase(Locale.ROOT) }
            .values
            .map { episodes -> episodes.sortedWith(EPISODE_COMPARATOR) }

        val previewItems = groups.map { episodes ->
            val first = episodes.first()
            val parsed = runCatching { FilenameTitleParser.parse(first.fileName) }.getOrNull()
            val sourceIds = episodes.map { it.sourceId }.distinct()
            val groupKey = first.groupKey ?: first.title.lowercase(Locale.ROOT)
            val artwork = artworkProvider.cachedArtwork(first.fileUri)
            val preview = MetaPreview(
                id = seriesId(sourceIds, groupKey),
                type = ContentType.SERIES,
                name = parsed?.title?.takeIf { it.isNotBlank() } ?: first.title,
                poster = artwork?.posterUri,
                posterShape = PosterShape.POSTER,
                background = artwork?.backdropUri,
                logo = null,
                description = null,
                releaseInfo = first.year?.takeIf { it.isNotBlank() } ?: parsed?.year,
                imdbRating = null,
                genres = emptyList(),
                seasonCount = episodes.mapNotNull { it.season }.maxOrNull(),
                sourceAddonBaseUrl = singleOrAggregateBaseUrl(sourceIds),
                localSourceName = localSourceLabel(sourceIds, sourceNames),
                localSourceIds = sourceIds
            )
            Triple(preview, episodes.maxOf { it.addedAt }, first)
        }

        val sorted = if (recentOnly) {
            previewItems.sortedByDescending { it.second }
        } else {
            previewItems.sortedBy { it.first.name.lowercase(Locale.ROOT) }
        }
        val limited = limit?.let(sorted::take) ?: sorted
        startArtworkPrefetch(
            limited.map { (preview, _, first) ->
                val parsed = runCatching { FilenameTitleParser.parse(first.fileName) }.getOrNull()
                ArtworkTarget(
                    previewId = preview.id,
                    fileUri = first.fileUri,
                    title = parsed?.title?.takeIf { it.isNotBlank() } ?: first.title,
                    year = first.year?.takeIf { it.isNotBlank() } ?: parsed?.year,
                    isSeries = true,
                    indexFileUris = items
                        .filter {
                            (it.groupKey ?: it.title.lowercase(Locale.ROOT)) ==
                                (first.groupKey ?: first.title.lowercase(Locale.ROOT))
                        }
                        .map { it.fileUri }
                        .distinct()
                )
            }
        )
        return limited.map { it.first }
    }

    private fun localSourceLabel(sourceIds: List<Long>, sourceNames: Map<Long, String>): String? {
        val names = sourceIds.mapNotNull { sourceNames[it] }.distinct()
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names.first()
            else -> context.getString(R.string.local_source_multiple)
        }
    }

    private fun singleOrAggregateBaseUrl(sourceIds: List<Long>): String =
        sourceIds.singleOrNull()?.let(::baseUrl) ?: LIBRARY_BASE_URL

    private fun movieGroupKey(item: LocalMediaItemEntity): String {
        val parsed = runCatching { FilenameTitleParser.parse(item.fileName) }.getOrNull()
        val title = parsed?.title?.takeIf { it.isNotBlank() } ?: item.title
        val year = item.year?.takeIf { it.isNotBlank() } ?: parsed?.year
        return "${title.lowercase(Locale.ROOT).trim()}|${year.orEmpty()}"
    }

    private fun startArtworkPrefetch(targets: List<ArtworkTarget>) {
        targets.forEach { target ->
            if (target.fileUri.isBlank()) return@forEach
            val jobKey = "${target.previewId}|${target.fileUri}"
            artworkScope.launch {
                val currentJob = coroutineContext[Job] ?: return@launch
                if (prefetchJobs.putIfAbsent(jobKey, currentJob) != null) return@launch
                try {
                    // Let the catalog row reach the UI before any artwork work starts.
                    delay(ARTWORK_PREFETCH_DELAY_MS)
                    prefetchSemaphore.withPermit {
                        val artwork = artworkProvider.resolveArtwork(
                            fileUri = target.fileUri,
                            title = target.title,
                            year = target.year,
                            isSeries = target.isSeries
                        )
                        val enrichment = runCatching {
                            cacheSearchMetadata(target, artwork?.tmdbId)
                        }.getOrNull()
                        if (artwork != null &&
                            (artwork.posterUri != null || artwork.backdropUri != null)
                        ) {
                            _artworkUpdates.emit(
                                LocalArtworkUpdate(
                                    previewId = target.previewId,
                                    artwork = artwork,
                                    enrichment = enrichment
                                )
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Could not resolve artwork for ${target.fileUri}", error)
                } finally {
                    prefetchJobs.remove(jobKey, currentJob)
                }
            }
        }
    }

    private fun aggregateAddon(
        kinds: Set<LocalMediaKind>,
        itemCount: Int
    ): Addon {
        val catalogs = buildList {
            if (LocalMediaKind.MOVIE in kinds) {
                add(
                    CatalogDescriptor(
                        type = ContentType.MOVIE,
                        id = CATALOG_RECENT_MOVIES,
                        name = context.getString(R.string.local_catalog_recent_movies),
                        showInHome = true,
                        hasExplicitShowInHome = true
                    )
                )
            }
            if (LocalMediaKind.SERIES_EPISODE in kinds) {
                add(
                    CatalogDescriptor(
                        type = ContentType.SERIES,
                        id = CATALOG_RECENT_SERIES,
                        name = context.getString(R.string.local_catalog_recent_series),
                        showInHome = true,
                        hasExplicitShowInHome = true
                    )
                )
            }
            if (LocalMediaKind.MOVIE in kinds) {
                add(
                    CatalogDescriptor(
                        type = ContentType.MOVIE,
                        id = CATALOG_MOVIES,
                        name = context.getString(R.string.local_catalog_movies),
                        showInHome = true,
                        hasExplicitShowInHome = true
                    )
                )
            }
            if (LocalMediaKind.SERIES_EPISODE in kinds) {
                add(
                    CatalogDescriptor(
                        type = ContentType.SERIES,
                        id = CATALOG_SERIES,
                        name = context.getString(R.string.local_catalog_series),
                        showInHome = true,
                        hasExplicitShowInHome = true
                    )
                )
            }
        }
        return Addon(
            id = LIBRARY_ADDON_ID,
            name = context.getString(R.string.local_catalog_library_name),
            displayName = aggregateDisplayName(),
            version = "local-$itemCount",
            description = null,
            logo = null,
            baseUrl = LIBRARY_BASE_URL,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }

    private fun displayName(source: LocalSourceEntity): String =
        context.getString(
            if (source.type == LocalSourceType.SMB) {
                R.string.local_source_display_name_smb
            } else {
                R.string.local_source_display_name
            },
            source.name
        )

    private fun aggregateDisplayName(): String =
        context.getString(R.string.local_catalog_library_name)

    companion object {
        private const val TAG = "LocalCatalog"
        private const val BASE_URL_PREFIX = "nuvio-local://"
        private const val ADDON_ID_PREFIX = "local."
        const val LIBRARY_BASE_URL = BASE_URL_PREFIX + "library"
        const val LIBRARY_ADDON_ID = ADDON_ID_PREFIX + "library"
        private const val MOVIE_PREFIX = "local:movie:"
        private const val SERIES_PREFIX = "local:series:"
        private const val ALL_SOURCES = -1L
        private const val CATALOG_RECENT_MOVIES = "recent_movies"
        private const val CATALOG_RECENT_SERIES = "recent_series"
        private const val CATALOG_MOVIES = "movies"
        private const val CATALOG_SERIES = "series"
        private const val ARTWORK_REPLAY_CACHE = 128
        private const val ARTWORK_PREFETCH_DELAY_MS = 150L
        private const val RECENT_ROW_LIMIT = 20
        private const val METADATA_SEARCH_MIN_QUERY_LENGTH = 2
        private const val METADATA_SEARCH_TIMEOUT_MS = 4_000L

        private val MOVIE_PRIMARY_COMPARATOR = compareBy<LocalMediaItemEntity> { it.fileSizeBytes }
            .thenBy { it.addedAt }
        private val EPISODE_COMPARATOR = compareBy<LocalMediaItemEntity>(
            { it.season ?: Int.MAX_VALUE },
            { it.episode ?: Int.MAX_VALUE },
            { it.fileName.lowercase(Locale.ROOT) }
        )

        fun isLocalAddon(baseUrl: String?): Boolean =
            baseUrl?.startsWith(BASE_URL_PREFIX) == true

        fun isLocalAddonId(addonId: String?): Boolean =
            addonId?.startsWith(ADDON_ID_PREFIX) == true

        fun isLocalItemId(itemId: String?): Boolean =
            itemId?.startsWith(MOVIE_PREFIX) == true || itemId?.startsWith(SERIES_PREFIX) == true

        fun isMovieItemId(itemId: String?): Boolean = itemId?.startsWith(MOVIE_PREFIX) == true

        fun seriesRef(itemId: String): Pair<Long, String>? {
            val rest = itemId.removePrefix(SERIES_PREFIX)
            val separator = rest.indexOf(':')
            if (separator <= 0) return null
            val sourcePart = rest.substring(0, separator)
            val sourceId = if (sourcePart == "all") {
                ALL_SOURCES
            } else {
                sourcePart.toLongOrNull() ?: return null
            }
            return sourceId to rest.substring(separator + 1)
        }

        fun baseUrl(sourceId: Long): String = BASE_URL_PREFIX + sourceId

        fun addonId(sourceId: Long): String = ADDON_ID_PREFIX + sourceId

        fun sourceId(baseUrl: String): Long? =
            if (!isLocalAddon(baseUrl)) null
            else baseUrl.removePrefix(BASE_URL_PREFIX).toLongOrNull()

        private fun seriesId(sourceIds: List<Long>, groupKey: String): String =
            if (sourceIds.size == 1) {
                "$SERIES_PREFIX${sourceIds.first()}:$groupKey"
            } else {
                "${SERIES_PREFIX}all:$groupKey"
            }
    }
}

private fun LocalMediaItemEntity.isDisplayableLibraryItem(): Boolean {
    if (fileName.startsWith(".")) return false
    return fileSizeBytes <= 0L || fileSizeBytes >= MIN_VISIBLE_FILE_BYTES
}
