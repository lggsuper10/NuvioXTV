@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.locallibrary.LocalSourceEntity
import com.nuvio.tv.data.locallibrary.SmbServerEntity
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.theme.NuvioTheme

/** A saved SMB server shown in the "Network shares" section. */
@Composable
internal fun SmbServerCard(
    server: SmbServerEntity,
    folders: List<LocalSourceEntity>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val online = folders.any { it.available }
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 124.dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.9f),
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = if (online) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Primary,
                    modifier = Modifier.width(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (online) R.string.local_library_folder_available
                        else R.string.local_library_folder_offline
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (online) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Primary
                )
            }
            Text(
                text = if (server.port == 445) server.host else "${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.local_media_smb_server_folders, folders.size),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.local_media_press_to_manage),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

/** The "add server" tile shown at the end of the network section. */
@Composable
internal fun AddSmbServerCard(
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Button(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 124.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.55f),
            contentColor = NuvioTheme.colors.TextSecondary,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = NuvioTheme.colors.OnSecondary,
            disabledContainerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.4f),
            disabledContentColor = NuvioTheme.colors.TextTertiary
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.width(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.local_media_smb_add_server),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.local_media_smb_add_server_hint),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.78f)
            )
        }
    }
}

/** Server page: the folders already added from this server plus the actions for the server. */
@Composable
internal fun SmbServerDetailContent(
    server: SmbServerEntity,
    folders: List<LocalSourceEntity>,
    isBusy: Boolean,
    addFocusRequester: FocusRequester?,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onEditAccount: () -> Unit,
    onRemoveServer: () -> Unit,
    onManageFolder: (LocalSourceEntity) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Button(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = if (server.port == 445) server.host else "${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
            Text(
                text = stringResource(R.string.local_media_smb_server_folders, folders.size),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        SettingsDialogActionRow(horizontalAlignment = Alignment.Start) {
            SettingsDialogActionButton(
                text = stringResource(R.string.local_media_smb_add_folder),
                onClick = { if (!isBusy) onAddFolder() },
                enabled = !isBusy,
                requestInitialFocus = addFocusRequester == null
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.local_media_smb_update_credentials),
                onClick = { if (!isBusy) onEditAccount() },
                enabled = !isBusy
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.local_media_smb_server_remove),
                onClick = { if (!isBusy) onRemoveServer() },
                enabled = !isBusy
            )
        }

        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyScreenState(
                    title = stringResource(R.string.local_media_smb_no_folders),
                    subtitle = stringResource(R.string.local_media_smb_no_folders_subtitle),
                    icon = Icons.Default.Folder,
                    height = 220.dp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "server_section_folders", span = { GridItemSpan(maxLineSpan) }) {
                        SourceSectionHeader(stringResource(R.string.local_media_smb_folders_section))
                    }
                    items(folders, key = { "server_folder_${it.id}" }) { source ->
                        LocalSourceCard(
                            source = source,
                            enabled = !isBusy,
                            onClick = { onManageFolder(source) },
                            onLongClick = { onManageFolder(source) }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = gridState)
            }
        }
    }
}
