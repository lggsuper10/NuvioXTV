package com.nuvio.tv.ui.screens.locallibrary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.locallibrary.LocalArtworkProvider
import com.nuvio.tv.core.locallibrary.LocalArtworkCandidate
import com.nuvio.tv.core.locallibrary.LocalArtwork
import com.nuvio.tv.core.locallibrary.LocalMediaPlayback
import com.nuvio.tv.core.locallibrary.FilenameTitleParser
import com.nuvio.tv.core.locallibrary.DoubanService
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.locallibrary.LocalCatalog
import com.nuvio.tv.data.locallibrary.LocalLibraryRepository
import com.nuvio.tv.data.locallibrary.LocalMediaItemEntity
import com.nuvio.tv.domain.model.ContentType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LocalDetailUiState(
    val title: String = "",
    val sourceName: String? = null,
    val posterUri: String? = null,
    val backdropUri: String? = null,
    val tmdbId: Int? = null,
    val enrichment: TmdbEnrichment? = null,
    val movie: LocalMediaItemEntity? = null,
    val episodes: List<LocalMediaItemEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: Boolean = false,
    val artworkBusy: Boolean = false,
    val artworkResult: LocalArtworkActionResult? = null,
    val posterSearchResults: List<LocalArtworkCandidate> = emptyList(),
    val isPosterSearching: Boolean = false,
    val posterSearchCompleted: Boolean = false
) {
    val isSeries: Boolean get() = episodes.isNotEmpty()
}

enum class LocalArtworkActionResult { UPDATED, FAILED }

private data class ResolvedLocalMetadata(
    val enrichment: TmdbEnrichment,
    val tmdbId: Int?
)

@HiltViewModel
class LocalDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: LocalCatalog,
    private val repository: LocalLibraryRepository,
    private val artworkProvider: LocalArtworkProvider,
    private val doubanService: DoubanService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val playback: LocalMediaPlayback
) : ViewModel() {
    private val localId: String = savedStateHandle.get<String>("localId").orEmpty()
    private val _uiState = MutableStateFlow(LocalDetailUiState())
    val uiState: StateFlow<LocalDetailUiState> = _uiState.asStateFlow()

    private var artworkFileUri: String? = null
    private var artworkTitle: String = ""
    private var artworkYear: String? = null
    private var artworkIsSeries: Boolean = false
    private var posterSearchGeneration: Int = 0
    private var metadataLoadJob: Job? = null

    init {
        viewModelScope.launch {
            if (LocalCatalog.isMovieItemId(localId)) {
                val movie = catalog.getMovie(localId)
                if (movie == null) {
                    _uiState.update { it.copy(isLoading = false, error = true) }
                } else {
                    val source = repository.getSource(movie.sourceId)
                    val artwork = artworkProvider.cachedArtwork(movie.fileUri)
                    val parsed = runCatching { FilenameTitleParser.parse(movie.fileName) }.getOrNull()
                    val displayTitle = parsed?.title?.takeIf { it.isNotBlank() } ?: movie.title
                    val displayYear = movie.year?.takeIf { it.isNotBlank() } ?: parsed?.year
                    _uiState.update {
                        it.copy(
                            title = displayTitle,
                            sourceName = source?.name,
                            posterUri = artwork?.posterUri,
                            backdropUri = artwork?.backdropUri,
                            tmdbId = artwork?.tmdbId,
                            movie = movie,
                            isLoading = false
                        )
                    }
                    artworkFileUri = movie.fileUri
                    artworkTitle = displayTitle
                    artworkYear = displayYear
                    artworkIsSeries = false
                    loadArtwork(movie.fileUri, displayTitle, displayYear, isSeries = false)
                    loadMetadata(displayTitle, displayYear, isSeries = false)
                }
                return@launch
            }

            val ref = LocalCatalog.seriesRef(localId)
            if (ref == null) {
                _uiState.update { it.copy(isLoading = false, error = true) }
                return@launch
            }
            val (sourceId, groupKey) = ref
            val episodes = catalog.getSeries(sourceId, groupKey)
            val source = repository.getSource(sourceId)
            val primaryItem = episodes.firstOrNull()
            val artwork = primaryItem?.let { artworkProvider.cachedArtwork(it.fileUri) }
            val parsed = primaryItem?.let {
                runCatching { FilenameTitleParser.parse(it.fileName) }.getOrNull()
            }
            val displayTitle = parsed?.title?.takeIf { it.isNotBlank() }
                ?: primaryItem?.title.orEmpty()
            val displayYear = primaryItem?.year?.takeIf { it.isNotBlank() } ?: parsed?.year
            _uiState.update {
                it.copy(
                    title = displayTitle,
                    sourceName = source?.name,
                    posterUri = artwork?.posterUri,
                    backdropUri = artwork?.backdropUri,
                    tmdbId = artwork?.tmdbId,
                    episodes = episodes,
                    isLoading = false,
                    error = episodes.isEmpty()
                )
            }
            if (primaryItem != null) {
                artworkFileUri = primaryItem.fileUri
                artworkTitle = displayTitle
                artworkYear = displayYear
                artworkIsSeries = true
                loadArtwork(
                    fileUri = primaryItem.fileUri,
                    title = displayTitle,
                    year = displayYear,
                    isSeries = true
                )
                loadMetadata(displayTitle, displayYear, isSeries = true)
            }
        }
    }

    private fun loadArtwork(
        fileUri: String,
        title: String,
        year: String?,
        isSeries: Boolean
    ) {
        viewModelScope.launch {
            val artwork = artworkProvider.resolveArtwork(
                fileUri = fileUri,
                title = title,
                year = year,
                isSeries = isSeries
            ) ?: return@launch
            if (artwork.posterUri == null && artwork.backdropUri == null) return@launch
            _uiState.update {
                it.copy(
                    posterUri = artwork.posterUri ?: it.posterUri,
                    backdropUri = artwork.backdropUri ?: it.backdropUri,
                    tmdbId = artwork.tmdbId ?: it.tmdbId
                )
            }
        }
    }

    private fun loadMetadata(title: String, year: String?, isSeries: Boolean) {
        metadataLoadJob?.cancel()
        metadataLoadJob = viewModelScope.launch {
            val resolved = resolveMetadata(
                preferredTmdbId = _uiState.value.tmdbId,
                title = title,
                year = year,
                isSeries = isSeries
            ) ?: return@launch
            _uiState.update {
                it.copy(
                    enrichment = resolved.enrichment,
                    tmdbId = resolved.tmdbId ?: it.tmdbId
                )
            }
            catalog.cacheSearchMetadata(localId, resolved.enrichment)
        }
    }

    private suspend fun resolveMetadata(
        preferredTmdbId: Int?,
        title: String,
        year: String?,
        isSeries: Boolean
    ): ResolvedLocalMetadata? {
        val mediaType = if (isSeries) "tv" else "movie"
        val contentType = if (isSeries) ContentType.SERIES else ContentType.MOVIE
        val language = Locale.getDefault().toLanguageTag()
            .takeIf { it.isNotBlank() }
            ?: "zh-CN"
        val tmdbId = preferredTmdbId ?: withTimeoutOrNull(6_000L) {
            bestEffort {
                tmdbService.searchImages(
                    title = title,
                    year = year,
                    mediaType = mediaType
                ).tmdbId
            }
        }
        if (tmdbId != null) {
            val enrichment = withTimeoutOrNull(8_000L) {
                bestEffort {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId.toString(),
                        contentType = contentType,
                        language = language
                    )
                }
            }
            if (enrichment != null) {
                return ResolvedLocalMetadata(enrichment = enrichment, tmdbId = tmdbId)
            }
        }

        val doubanEnrichment = withTimeoutOrNull(10_000L) {
            bestEffort {
                doubanService.fetchDetails(
                    title = title,
                    year = year,
                    isSeries = isSeries
                )?.toTmdbEnrichment()
            }
        }
        return doubanEnrichment?.let {
            ResolvedLocalMetadata(enrichment = it, tmdbId = tmdbId)
        }
    }

    private suspend fun <T> bestEffort(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun publishArtwork(
        artwork: LocalArtwork,
        enrichment: TmdbEnrichment? = null
    ): Boolean {
        if (artwork.posterUri == null && artwork.backdropUri == null) return false
        _uiState.update {
            it.copy(
                posterUri = artwork.posterUri ?: it.posterUri,
                backdropUri = artwork.backdropUri ?: it.backdropUri,
                tmdbId = artwork.tmdbId ?: it.tmdbId
            )
        }
        catalog.notifyArtworkUpdated(localId, artwork, enrichment)
        return true
    }

    fun refreshArtwork() {
        val fileUri = artworkFileUri ?: return
        if (_uiState.value.artworkBusy) return
        _uiState.update {
            it.copy(artworkBusy = true, artworkResult = null)
        }
        viewModelScope.launch {
            val artwork = bestEffort {
                artworkProvider.refreshArtwork(
                    fileUri = fileUri,
                    title = artworkTitle,
                    year = artworkYear,
                    isSeries = artworkIsSeries
                )
            }
            val updated = artwork?.let(::publishArtwork) == true
            _uiState.update {
                it.copy(
                    artworkBusy = false,
                    artworkResult = if (updated) {
                        LocalArtworkActionResult.UPDATED
                    } else {
                        LocalArtworkActionResult.FAILED
                    }
                )
            }
        }
    }

    fun searchPoster(query: String) {
        val cleanQuery = query.trim().ifBlank { artworkTitle }.trim()
        if (cleanQuery.isBlank()) return
        val generation = ++posterSearchGeneration
        _uiState.update {
            it.copy(
                isPosterSearching = true,
                posterSearchCompleted = false,
                posterSearchResults = emptyList()
            )
        }
        viewModelScope.launch {
            val results = runCatching {
                artworkProvider.searchCandidates(
                    query = cleanQuery,
                    year = artworkYear,
                    isSeries = artworkIsSeries
                )
            }.getOrDefault(emptyList())
            if (generation != posterSearchGeneration) return@launch
            _uiState.update {
                it.copy(
                    isPosterSearching = false,
                    posterSearchCompleted = true,
                    posterSearchResults = results
                )
            }
        }
    }

    fun applyPoster(candidate: LocalArtworkCandidate) {
        val fileUri = artworkFileUri ?: return
        if (_uiState.value.artworkBusy) return
        val selectedTitle = candidate.title.trim().ifBlank { artworkTitle }
        val selectedYear = candidate.year?.trim()?.takeIf { it.isNotBlank() } ?: artworkYear
        val isSeries = artworkIsSeries
        metadataLoadJob?.cancel()
        _uiState.update {
            it.copy(
                title = selectedTitle,
                tmdbId = candidate.tmdbId,
                artworkBusy = true,
                artworkResult = null
            )
        }
        metadataLoadJob = viewModelScope.launch {
            val artworkDeferred = async {
                bestEffort {
                    artworkProvider.applyManualArtwork(fileUri, candidate)
                }
            }
            val metadataDeferred = async {
                resolveMetadata(
                    preferredTmdbId = candidate.tmdbId,
                    title = selectedTitle,
                    year = selectedYear,
                    isSeries = isSeries
                )
            }

            val artwork = artworkDeferred.await()
            val artworkUpdated = artwork?.let(::publishArtwork) == true
            artworkTitle = selectedTitle
            artworkYear = selectedYear

            val metadata = metadataDeferred.await()
            metadata?.enrichment?.localizedTitle
                ?.takeIf { it.isNotBlank() }
                ?.let { artworkTitle = it }
            metadata?.enrichment?.releaseInfo
                ?.take(4)
                ?.takeIf { it.length == 4 }
                ?.let { artworkYear = it }

            _uiState.update {
                it.copy(
                    title = metadata?.enrichment?.localizedTitle
                        ?.takeIf { title -> title.isNotBlank() }
                        ?: selectedTitle,
                    enrichment = metadata?.enrichment ?: it.enrichment,
                    tmdbId = candidate.tmdbId ?: metadata?.tmdbId ?: it.tmdbId,
                    artworkBusy = false,
                    artworkResult = if (artworkUpdated || metadata != null) {
                        LocalArtworkActionResult.UPDATED
                    } else {
                        LocalArtworkActionResult.FAILED
                    }
                )
            }

            if (metadata != null) {
                catalog.cacheSearchMetadata(localId, metadata.enrichment)
                val currentArtwork = artwork ?: LocalArtwork(
                    posterUri = _uiState.value.posterUri,
                    backdropUri = _uiState.value.backdropUri,
                    tmdbId = candidate.tmdbId ?: metadata.tmdbId
                )
                catalog.notifyArtworkUpdated(
                    previewId = localId,
                    artwork = currentArtwork,
                    enrichment = metadata.enrichment
                )
            }
        }
    }

    fun clearPosterSearch() {
        posterSearchGeneration++
        _uiState.update {
            it.copy(
                posterSearchResults = emptyList(),
                isPosterSearching = false,
                posterSearchCompleted = false
            )
        }
    }

    fun playerRoute(item: LocalMediaItemEntity): String =
        playback.playerRoute(
            item = item,
            contentId = localId,
            poster = _uiState.value.posterUri,
            backdrop = _uiState.value.backdropUri
        )
}

