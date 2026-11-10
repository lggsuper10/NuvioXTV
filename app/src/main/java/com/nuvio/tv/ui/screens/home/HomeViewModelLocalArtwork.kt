package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

internal fun HomeViewModel.observeLocalArtworkUpdatesPipeline() {
    viewModelScope.launch {
        localCatalog.artworkUpdates.collect { update ->
            var changed = updateIndexedCatalogItem(update.previewId) { current ->
                current.copy(
                    poster = update.artwork.posterUri ?: current.poster,
                    background = update.artwork.backdropUri ?: current.background
                )
            }
            update.enrichment?.let { enrichment ->
                updateCatalogItemWithTmdb(
                    update.previewId,
                    enrichment.copy(backdrop = null)
                )
                changed = true
            }
            if (changed) {
                scheduleUpdateCatalogRows()
            }
            val visibleContinueWatching =
                _uiState.value.continueWatchingItems + _uiState.value.upcomingItems
            val isVisibleInContinueWatching = visibleContinueWatching.any { item ->
                when (item) {
                    is ContinueWatchingItem.InProgress -> item.progress.contentId == update.previewId
                    is ContinueWatchingItem.NextUp -> item.info.contentId == update.previewId
                }
            }
            if (isVisibleInContinueWatching) {
                cwPipelineRefreshTrigger.value += 1
            }
        }
    }
}
