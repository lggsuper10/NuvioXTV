package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.smb.SmbServerConfig
import com.nuvio.tv.data.locallibrary.LocalLibraryRepository
import com.nuvio.tv.data.locallibrary.LocalSourceEntity
import com.nuvio.tv.data.locallibrary.SmbServerEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalMediaUiState(
    val sources: List<LocalSourceEntity> = emptyList(),
    val servers: List<SmbServerEntity> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val serverTestRunning: Boolean = false,
    val serverTestSucceeded: Boolean? = null,
    val serverTestMessage: String? = null,
    val serverTestShares: List<String> = emptyList()
)

@HiltViewModel
class LocalMediaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LocalLibraryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(LocalMediaUiState())
    val uiState: StateFlow<LocalMediaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.refreshAvailability(force = true)
        }
        viewModelScope.launch {
            repository.observeSources().collectLatest { sources ->
                _uiState.update { it.copy(sources = sources) }
            }
        }
        viewModelScope.launch {
            repository.observeServers().collectLatest { servers ->
                _uiState.update { it.copy(servers = servers) }
            }
        }
    }

    fun addSource(treeUri: String) {
        if (_uiState.value.sources.any { it.treeUri == treeUri }) {
            _uiState.update { it.copy(message = context.getString(R.string.local_media_source_exists)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val sourceId = repository.addSource("", treeUri)
            val result = repository.scanSource(sourceId)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.error ?: context.getString(R.string.local_media_scanned, result.itemCount)
                )
            }
        }
    }

    // --- SMB servers -------------------------------------------------------

    fun testServer(config: SmbServerConfig) {
        validateServer(config)?.let { error ->
            _uiState.update {
                it.copy(
                    serverTestRunning = false,
                    serverTestSucceeded = false,
                    serverTestMessage = error,
                    serverTestShares = emptyList()
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    serverTestRunning = true,
                    serverTestSucceeded = null,
                    serverTestMessage = null,
                    serverTestShares = emptyList()
                )
            }
            val result = runCatching { repository.testServer(config) }
            _uiState.update {
                it.copy(
                    serverTestRunning = false,
                    serverTestSucceeded = result.isSuccess,
                    serverTestMessage = result.exceptionOrNull()?.let { error ->
                        smbErrorMessage(config.host, error)
                    } ?: context.getString(R.string.local_media_smb_connection_ok),
                    serverTestShares = result.getOrDefault(emptyList())
                )
            }
        }
    }

    fun clearServerTest() {
        _uiState.update {
            it.copy(
                serverTestRunning = false,
                serverTestSucceeded = null,
                serverTestMessage = null,
                serverTestShares = emptyList()
            )
        }
    }

    fun saveServer(
        config: SmbServerConfig,
        serverId: Long? = null,
        onComplete: (Long?) -> Unit = {}
    ) {
        validateServer(config)?.let { error ->
            _uiState.update { it.copy(message = error, serverTestSucceeded = false, serverTestMessage = error) }
            onComplete(null)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = runCatching { repository.saveServer(config, serverId) }
            val savedId = result.getOrNull()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.exceptionOrNull()?.let { error -> smbErrorMessage(config.host, error) }
                )
            }
            onComplete(savedId)
        }
    }

    fun removeServer(server: SmbServerEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            runCatching { repository.removeServer(server.id) }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = context.getString(R.string.local_media_smb_server_removed, server.name)
                )
            }
        }
    }

    fun addServerFolder(
        serverId: Long,
        share: String,
        path: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = runCatching {
                val sourceId = repository.addServerFolder(serverId, share, path)
                repository.scanSource(sourceId)
            }
            val scan = result.getOrNull()
            val failure = result.exceptionOrNull()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = failure?.let { error -> smbErrorMessage(null, error) }
                        ?: scan?.error?.let { error -> smbErrorMessage(null, error) }
                        ?: context.getString(R.string.local_media_scanned, scan?.itemCount ?: 0)
                )
            }
            onComplete(failure == null && scan?.error == null)
        }
    }

    fun scan(source: LocalSourceEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = repository.scanSource(source.id)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.error ?: context.getString(R.string.local_media_scanned, result.itemCount)
                )
            }
        }
    }

    fun scanAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = repository.scanAll()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.error ?: context.getString(R.string.local_media_scanned, result.itemCount)
                )
            }
        }
    }

    fun remove(source: LocalSourceEntity) {
        viewModelScope.launch {
            repository.removeSource(source.id)
            _uiState.update {
                it.copy(message = context.getString(R.string.local_media_removed, source.name))
            }
        }
    }

    fun storagePermissionDenied() {
        _uiState.update {
            it.copy(message = context.getString(R.string.local_media_storage_permission_denied))
        }
    }

    private fun validateServer(config: SmbServerConfig): String? {
        if (config.host.isBlank()) {
            return context.getString(R.string.local_media_smb_invalid_server_or_share)
        }
        if (config.port !in 1..65535) {
            return context.getString(R.string.local_media_smb_invalid_port)
        }
        return null
    }

    private fun smbErrorMessage(host: String?, error: Throwable): String {
        val messages = ArrayList<String>()
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            current.message?.takeIf { it.isNotBlank() }?.let(messages::add)
            current = current.cause
        }
        val details = messages.joinToString(" ").ifBlank { error.javaClass.simpleName }
        return smbErrorMessage(host, details)
    }

    private fun smbErrorMessage(host: String?, details: String): String {
        val lower = details.lowercase()
        return when {
            lower.contains("status_account_disabled") ->
                context.getString(R.string.local_media_smb_account_disabled)

            listOf(
                "status_logon_failure",
                "status_access_denied",
                "status_password_expired",
                "status_account_restriction",
                "status_invalid_logon_hours",
                "logon failure",
                "authentication failed"
            ).any { lower.contains(it) } -> context.getString(R.string.local_media_smb_auth_failed)

            listOf(
                "connectexception",
                "unknownhost",
                "noroutetohost",
                "timed out",
                "timeout",
                "unreachable",
                "connection refused",
                "no route"
            ).any { lower.contains(it) } -> if (host.isNullOrBlank()) {
                context.getString(R.string.local_media_smb_connection_failed, details)
            } else {
                context.getString(R.string.local_media_smb_server_unreachable, host)
            }

            else -> context.getString(R.string.local_media_smb_connection_failed, details)
        }
    }
}
