package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.locallibrary.LocalCatalog
import com.nuvio.tv.data.locallibrary.LocalLibraryRepository
import com.nuvio.tv.data.locallibrary.LocalSourceEntity
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LocalLibraryFilter {
    ALL,
    MOVIES,
    SERIES
}

enum class LocalLibrarySort {
    RECENT,
    TITLE
}

enum class LocalLibraryBrowseMode {
    ITEMS,
    FOLDERS
}

data class LocalFolderSummary(
    val source: LocalSourceEntity,
    val movieCount: Int,
    val seriesCount: Int
 ) {
    val itemCount: Int get() = movieCount + seriesCount
}

data class LocalLibraryUiState(
    val sources: List<LocalSourceEntity> = emptyList(),
    val movies: List<MetaPreview> = emptyList(),
    val series: List<MetaPreview> = emptyList(),
    val selectedFilter: LocalLibraryFilter = LocalLibraryFilter.ALL,
    val selectedSourceId: Long? = null,
    val sort: LocalLibrarySort = LocalLibrarySort.RECENT,
    val browseMode: LocalLibraryBrowseMode = LocalLibraryBrowseMode.ITEMS,
    val isLoading: Boolean = true
) {
    val sourceOptions: List<LocalSourceEntity> get() = sources
    val availableSources: List<LocalSourceEntity> get() = sources.filter { it.available }

    val folderSummaries: List<LocalFolderSummary>
        get() = sources.map { source ->
            LocalFolderSummary(
                source = source,
                movieCount = movies.count { source.id in it.localSourceIds },
                seriesCount = series.count { source.id in it.localSourceIds }
            )
        }

    val visibleItems: List<MetaPreview>
        get() {
            val items = when (selectedFilter) {
                LocalLibraryFilter.ALL -> movies + series
                LocalLibraryFilter.MOVIES -> movies
                LocalLibraryFilter.SERIES -> series
            }
            val filtered = selectedSourceId?.let { sourceId ->
                items.filter { sourceId in it.localSourceIds }
            } ?: items
            return if (sort == LocalLibrarySort.TITLE) {
                filtered.sortedBy { it.name.lowercase() }
            } else {
                filtered
            }
        }

    val isEmpty: Boolean get() = !isLoading && visibleItems.isEmpty()
    val hasSources: Boolean get() = sources.isNotEmpty()
    val selectedSourceUnavailable: Boolean
        get() = selectedSourceId?.let { id -> sources.any { it.id == id && !it.available } } == true
}

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val catalog: LocalCatalog,
    repository: LocalLibraryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocalLibraryUiState())
    val uiState: StateFlow<LocalLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeSources(),
                repository.observeAllItems()
            ) { _, _ -> }.collectLatest { load() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            catalog.refreshAvailability(force = true)
            load()
        }
    }

    fun selectFilter(filter: LocalLibraryFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun selectSource(sourceId: Long?) {
        _uiState.update { it.copy(selectedSourceId = sourceId) }
    }

    fun selectSort(sort: LocalLibrarySort) {
        _uiState.update { it.copy(sort = sort) }
    }

    fun selectBrowseMode(mode: LocalLibraryBrowseMode) {
        _uiState.update { it.copy(browseMode = mode) }
    }

    private suspend fun load() {
        catalog.refreshAvailability()
        val sources = catalog.allSources()
        val movies = catalog.previews(ContentType.MOVIE, recentOnly = true)
        val series = catalog.previews(ContentType.SERIES, recentOnly = true)
        _uiState.update { current ->
            val selectedSourceId = current.selectedSourceId
                ?.takeIf { id -> sources.any { it.id == id } }
            current.copy(
                sources = sources,
                movies = movies,
                series = series,
                selectedSourceId = selectedSourceId,
                isLoading = false
            )
        }
    }
}
