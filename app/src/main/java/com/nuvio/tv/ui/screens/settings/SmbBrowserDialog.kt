@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogButton
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Browses one saved SMB server: share list first, then the folder tree inside a share.
 * The caller receives the selected (share, path) and turns it into a media source.
 */
@Composable
internal fun SmbBrowserDialog(
    serverId: Long,
    onAdd: (share: String, path: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: SmbBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(serverId) { viewModel.start(serverId) }

    var manualMode by rememberSaveable(serverId) { mutableStateOf(false) }
    var manualShare by rememberSaveable(serverId) { mutableStateOf("") }

    val atShares = state.share == null
    val breadcrumb = (listOf(state.serverName) + state.breadcrumb)
        .filter { it.isNotBlank() }
        .joinToString(" / ")

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.local_media_smb_browse_title),
        subtitle = breadcrumb.ifBlank { null },
        width = 880.dp,
        suppressFirstKeyUp = true
    ) {
        if (manualMode) {
            Text(
                text = stringResource(R.string.local_media_smb_browse_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            InputField(
                value = manualShare,
                onValueChange = { manualShare = it },
                placeholder = stringResource(R.string.local_media_smb_share_hint),
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (atShares && state.sharesUnavailable) {
            Text(
                text = stringResource(R.string.local_media_smb_browse_shares_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        } else {
            key(state.share.orEmpty(), state.path) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.canGoUp) {
                    item(key = "up") {
                        NuvioDialogButton(
                            text = stringResource(R.string.local_media_smb_browse_up),
                            onClick = viewModel::goUp,
                            requestInitialFocus = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                val rows = if (atShares) {
                    state.shares.map { share -> share to "" }
                } else {
                    state.folders.map { folder -> folder.name to folder.path }
                }
                itemsIndexed(
                    items = rows,
                    key = { _, row -> "row_${row.first}" }
                ) { index, row ->
                    val (name, path) = row
                    NuvioDialogButton(
                        text = name,
                        onClick = {
                            if (atShares) viewModel.openShare(name) else viewModel.openFolder(path)
                        },
                        requestInitialFocus = index == 0 && !state.canGoUp,
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.width(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (rows.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(
                                if (atShares) {
                                    R.string.local_media_smb_browse_no_shares
                                } else {
                                    R.string.local_media_smb_browse_no_folders
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            }
        }

        if (state.loading) {
            Text(
                text = stringResource(R.string.local_media_smb_browse_loading),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        } else {
            val error = state.error
            if (error != null) {
                Text(
                    text = stringResource(R.string.local_media_smb_browse_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            if (manualMode) {
                SettingsDialogActionButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.local_media_smb_browse_manual_cancel),
                    onClick = {
                        manualMode = false
                        manualShare = ""
                    }
                )
                SettingsDialogActionButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.action_select),
                    onClick = {
                        val share = manualShare.trim()
                        if (share.isNotEmpty()) {
                            manualMode = false
                            viewModel.openShare(share)
                        }
                    },
                    enabled = manualShare.isNotBlank() && !state.loading
                )
            } else if (atShares) {
                SettingsDialogActionButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.local_media_smb_browse_manual),
                    onClick = { manualMode = true }
                )
            } else {
                SettingsDialogActionButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.local_media_smb_browse_add_here),
                    onClick = {
                        val share = state.share
                        if (share != null) onAdd(share, state.path)
                    },
                    enabled = state.share != null && !state.loading
                )
            }
        }
    }
}
