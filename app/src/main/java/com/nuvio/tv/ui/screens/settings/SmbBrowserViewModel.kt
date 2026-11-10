package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.smb.SmbFolder
import com.nuvio.tv.data.locallibrary.LocalLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SmbBrowserUiState(
    val serverName: String = "",
    val loading: Boolean = false,
    /** Null while the share list is shown; set once a share is opened. */
    val share: String? = null,
    val path: String = "",
    val shares: List<String> = emptyList(),
    val folders: List<SmbFolder> = emptyList(),
    val error: String? = null,
    /** True when the server refuses share enumeration, so only a typed share name can be used. */
    val sharesUnavailable: Boolean = false
) {
    val canGoUp: Boolean
        get() = share != null && (path.isNotEmpty() || !sharesUnavailable)

    val breadcrumb: List<String>
        get() = buildList {
            share?.let { add(it) }
            path.split('/').filter { it.isNotBlank() }.forEach(::add)
        }
}

@HiltViewModel
class SmbBrowserViewModel @Inject constructor(
    private val repository: LocalLibraryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SmbBrowserUiState())
    val uiState: StateFlow<SmbBrowserUiState> = _uiState.asStateFlow()

    private var serverId: Long? = null
    private var loadJob: Job? = null

    fun start(serverId: Long) {
        if (this.serverId == serverId) return
        this.serverId = serverId
        loadJob?.cancel()
        _uiState.value = SmbBrowserUiState()
        viewModelScope.launch {
            val server = repository.getServer(serverId)
            _uiState.update { it.copy(serverName = server?.name.orEmpty()) }
            showShares()
        }
    }

    fun openShare(share: String) {
        val clean = share.trim().trim('/', '\\')
        if (clean.isBlank()) return
        _uiState.update {
            it.copy(share = clean, path = "", folders = emptyList(), error = null)
        }
        loadFolders(clean, "")
    }

    fun openFolder(path: String) {
        val share = _uiState.value.share ?: return
        _uiState.update { it.copy(path = path, error = null) }
        loadFolders(share, path)
    }

    fun goUp() {
        val state = _uiState.value
        val share = state.share ?: return
        if (state.path.isEmpty()) {
            if (state.sharesUnavailable) return
            _uiState.update {
                it.copy(share = null, path = "", folders = emptyList(), error = null)
            }
            if (state.shares.isEmpty()) showShares() else Unit
        } else {
            openFolder(state.path.substringBeforeLast('/', missingDelimiterValue = ""))
        }
    }

    fun retry() {
        val state = _uiState.value
        val share = state.share
        if (share == null) showShares() else loadFolders(share, state.path)
    }

    private fun showShares() {
        val id = serverId ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val result = runCatching { repository.listServerShares(id) }
            val shares = result.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    loading = false,
                    shares = shares,
                    sharesUnavailable = result.isFailure && shares.isEmpty(),
                    error = result.exceptionOrNull()?.let { error -> error.message ?: error.javaClass.simpleName }
                )
            }
        }
    }

    private fun loadFolders(share: String, path: String) {
        val id = serverId ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, folders = emptyList(), error = null) }
            val result = runCatching { repository.listServerFolders(id, share, path) }
            _uiState.update {
                it.copy(
                    loading = false,
                    folders = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { error ->
                        error.message ?: error.javaClass.simpleName
                    }
                )
            }
        }
    }
}
