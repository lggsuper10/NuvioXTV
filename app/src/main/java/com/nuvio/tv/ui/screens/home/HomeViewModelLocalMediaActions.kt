package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.locallibrary.LocalCatalog
import com.nuvio.tv.data.locallibrary.LocalMediaDeleteResult
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun HomeViewModel.refreshPosterLocalDeleteState(item: MetaPreview) {
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (!LocalCatalog.isLocalItemId(item.id)) {
        _uiState.update { state ->
            if (statusKey !in state.posterLocalDeleteCounts) {
                state
            } else {
                state.copy(posterLocalDeleteCounts = state.posterLocalDeleteCounts - statusKey)
            }
        }
        return
    }

    viewModelScope.launch {
        val count = runCatching {
            localCatalog.getDeletableLocalItems(item.id).size
        }.getOrDefault(0)
        _uiState.update { state ->
            state.copy(
                posterLocalDeleteCounts = state.posterLocalDeleteCounts + (statusKey to count)
            )
        }
    }
}

fun HomeViewModel.deletePosterLocalMedia(
    item: MetaPreview,
    onResult: (LocalMediaDeleteResult) -> Unit
) {
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.posterLocalDeletePending) return

    _uiState.update { state ->
        state.copy(posterLocalDeletePending = state.posterLocalDeletePending + statusKey)
    }
    viewModelScope.launch {
        val result = runCatching {
            localCatalog.deleteLocalMedia(item.id)
        }.getOrElse {
            LocalMediaDeleteResult(
                requestedCount = 0,
                deletedCount = 0,
                failedCount = 1
            )
        }
        _uiState.update { state ->
            state.copy(
                posterLocalDeletePending = state.posterLocalDeletePending - statusKey,
                posterLocalDeleteCounts = state.posterLocalDeleteCounts - statusKey
            )
        }
        onResult(result)
    }
}
