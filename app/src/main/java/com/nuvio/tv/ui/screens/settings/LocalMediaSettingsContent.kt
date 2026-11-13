@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.locallibrary.LocalStorageAccess
import com.nuvio.tv.data.locallibrary.LocalSourceEntity
import com.nuvio.tv.data.locallibrary.LocalSourceType
import com.nuvio.tv.data.locallibrary.SmbServerEntity
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogButton
import com.nuvio.tv.ui.components.NuvioDialogConfirmationButtons
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LocalMediaSettingsContent(
    initialFocusRequester: FocusRequester?,
    openSmbOnStart: Boolean = false,
    viewModel: LocalMediaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFolderBrowser by rememberSaveable { mutableStateOf(false) }
    var showAddSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showServerDialog by rememberSaveable { mutableStateOf(openSmbOnStart) }
    var editingServer by remember { mutableStateOf<SmbServerEntity?>(null) }
    var openServerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var browseServerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingServerRemoval by remember { mutableStateOf<SmbServerEntity?>(null) }
    var pendingActions by remember { mutableStateOf<LocalSourceEntity?>(null) }
    var pendingRemoval by remember { mutableStateOf<LocalSourceEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addSource(uri.toString())
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.isNotEmpty() && granted.values.all { it }) {
            showFolderBrowser = true
        } else {
            viewModel.storagePermissionDenied()
        }
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (LocalStorageAccess.canBrowseFiles(context)) {
            showFolderBrowser = true
        } else {
            viewModel.storagePermissionDenied()
        }
    }
    fun openBuiltInFolderBrowser() {
        when {
            // Deleting files from a file:// source needs write access too, not just read.
            LocalStorageAccess.canBrowseFiles(context) &&
                LocalStorageAccess.canWriteLocalFiles(context) -> showFolderBrowser = true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                allFilesAccessLauncher.launch(
                    LocalStorageAccess.allFilesAccessIntent(context)
                        ?: error("No all-files access settings activity")
                )
            }.onFailure { viewModel.storagePermissionDenied() }
            else -> storagePermissionLauncher.launch(LocalStorageAccess.runtimePermissions())
        }
    }
    fun openFolderPicker() {
        runCatching { picker.launch(null) }
            .onFailure { openBuiltInFolderBrowser() }
    }
    val storageRoots = remember(showFolderBrowser) {
        if (showFolderBrowser) LocalStorageAccess.roots(context) else emptyList()
    }
    val openServer = uiState.servers.firstOrNull { it.id == openServerId }

    if (openServer != null) {
        SmbServerDetailContent(
            server = openServer,
            folders = uiState.sources.filter { it.serverId == openServer.id },
            isBusy = uiState.isBusy,
            addFocusRequester = initialFocusRequester,
            onBack = { openServerId = null },
            onAddFolder = { browseServerId = openServer.id },
            onEditAccount = {
                editingServer = openServer
                showServerDialog = true
            },
            onRemoveServer = { pendingServerRemoval = openServer },
            onManageFolder = { pendingActions = it }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_local_media),
                subtitle = stringResource(R.string.settings_local_media_subtitle)
            )
            uiState.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            LocalMediaToolbar(
                canRescanAll = uiState.sources.isNotEmpty(),
                enabled = !uiState.isBusy,
                addFocusRequester = initialFocusRequester,
                onAddSource = { showAddSourceDialog = true },
                onRescanAll = viewModel::scanAll
            )
            if (uiState.isBusy) {
                Text(
                    text = stringResource(R.string.local_media_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (uiState.sources.isEmpty() && uiState.servers.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.local_media_empty),
                        subtitle = stringResource(R.string.local_media_empty_subtitle),
                        icon = Icons.Default.Folder,
                        height = 220.dp
                    )
                }
            } else {
                val deviceSources = uiState.sources.filter { it.type != LocalSourceType.SMB }
                val orphanSmbSources = uiState.sources.filter {
                    it.type == LocalSourceType.SMB && it.serverId == null
                }
                val showNetworkSection = uiState.servers.isNotEmpty() || orphanSmbSources.isNotEmpty()
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
                        if (deviceSources.isNotEmpty()) {
                            item(key = "local_section_device", span = { GridItemSpan(maxLineSpan) }) {
                                SourceSectionHeader(stringResource(R.string.local_media_source_section_device))
                            }
                            items(deviceSources, key = { "local_source_${it.id}" }) { source ->
                                LocalSourceCard(
                                    source = source,
                                    enabled = !uiState.isBusy,
                                    onClick = { pendingActions = source },
                                    onLongClick = { pendingActions = source }
                                )
                            }
                        }
                        if (showNetworkSection) {
                            item(key = "local_section_network", span = { GridItemSpan(maxLineSpan) }) {
                                SourceSectionHeader(stringResource(R.string.local_media_source_section_network))
                            }
                            items(uiState.servers, key = { "smb_server_${it.id}" }) { server ->
                                SmbServerCard(
                                    server = server,
                                    folders = uiState.sources.filter { it.serverId == server.id },
                                    enabled = !uiState.isBusy,
                                    onClick = { openServerId = server.id }
                                )
                            }
                            items(orphanSmbSources, key = { "local_source_${it.id}" }) { source ->
                                LocalSourceCard(
                                    source = source,
                                    enabled = !uiState.isBusy,
                                    onClick = { pendingActions = source },
                                    onLongClick = { pendingActions = source }
                                )
                            }
                            item(key = "smb_add_server", span = { GridItemSpan(maxLineSpan) }) {
                                AddSmbServerCard(
                                    enabled = !uiState.isBusy,
                                    onClick = {
                                        editingServer = null
                                        showServerDialog = true
                                    }
                                )
                            }
                        }
                    }
                    SettingsVerticalScrollIndicators(state = gridState)
                }
            }
        }
    }

    if (showAddSourceDialog) {
        AddSourceDialog(
            onDismiss = { showAddSourceDialog = false },
            onAddDevice = {
                showAddSourceDialog = false
                openFolderPicker()
            },
            onAddNetwork = {
                showAddSourceDialog = false
                editingServer = null
                showServerDialog = true
            }
        )
    }

    if (showFolderBrowser) {
        LocalFolderBrowserDialog(
            roots = storageRoots,
            onSelect = { treeUri ->
                showFolderBrowser = false
                viewModel.addSource(treeUri)
            },
            onDismiss = { showFolderBrowser = false }
        )
    }

    if (showServerDialog) {
        SmbServerDialog(
            initialServer = editingServer,
            serverTestRunning = uiState.serverTestRunning,
            serverTestSucceeded = uiState.serverTestSucceeded,
            serverTestMessage = uiState.serverTestMessage,
            serverTestShares = uiState.serverTestShares,
            isBusy = uiState.isBusy,
            onTest = viewModel::testServer,
            onSave = { config ->
                val isNewServer = editingServer == null
                viewModel.saveServer(config, editingServer?.id) { savedId ->
                    if (savedId != null) {
                        showServerDialog = false
                        editingServer = null
                        if (isNewServer) browseServerId = savedId
                    }
                }
            },
            onDismiss = {
                showServerDialog = false
                editingServer = null
            },
            onClearTest = viewModel::clearServerTest
        )
    }

    browseServerId?.let { serverId ->
        SmbBrowserDialog(
            serverId = serverId,
            onAdd = { share, path ->
                browseServerId = null
                viewModel.addServerFolder(serverId, share, path) { saved ->
                    if (saved) openServerId = serverId
                }
            },
            onDismiss = { browseServerId = null }
        )
    }

    pendingServerRemoval?.let { server ->
        val folderCount = uiState.sources.count { it.serverId == server.id }
        NuvioDialog(
            onDismiss = { pendingServerRemoval = null },
            title = stringResource(R.string.local_media_smb_server_remove_title),
            subtitle = stringResource(
                R.string.local_media_smb_server_remove_subtitle,
                server.name,
                folderCount
            ),
            suppressFirstKeyUp = true
        ) {
            NuvioDialogConfirmationButtons(
                confirmLabel = stringResource(R.string.local_media_smb_server_remove),
                onConfirm = {
                    viewModel.removeServer(server)
                    pendingServerRemoval = null
                    openServerId = null
                },
                onDismiss = { pendingServerRemoval = null }
            )
        }
    }

    pendingActions?.let { source ->
        NuvioDialog(
            onDismiss = { pendingActions = null },
            title = sourceDisplayName(source),
            subtitle = listOf(
                sourceLocation(source),
                stringResource(R.string.local_media_actions_subtitle)
            ).joinToString("\n"),
            suppressFirstKeyUp = true
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                NuvioDialogButton(
                    text = stringResource(R.string.local_media_rescan),
                    onClick = {
                        pendingActions = null
                        viewModel.scan(source)
                    },
                    enabled = !uiState.isBusy,
                    requestInitialFocus = true,
                    modifier = Modifier.fillMaxWidth()
                )
                NuvioDialogButton(
                    text = stringResource(R.string.local_media_remove),
                    onClick = {
                        pendingActions = null
                        pendingRemoval = source
                    },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                NuvioDialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pendingActions = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    pendingRemoval?.let { source ->
        NuvioDialog(
            onDismiss = { pendingRemoval = null },
            title = stringResource(
                if (source.type == LocalSourceType.SMB) {
                    R.string.local_media_remove_smb_confirm_title
                } else {
                    R.string.local_media_remove_confirm_title
                }
            ),
            subtitle = stringResource(R.string.local_media_remove_confirm_subtitle),
            suppressFirstKeyUp = true
        ) {
            NuvioDialogConfirmationButtons(
                confirmLabel = stringResource(R.string.local_media_remove),
                onConfirm = {
                    viewModel.remove(source)
                    pendingRemoval = null
                },
                onDismiss = { pendingRemoval = null }
            )
        }
    }
}

@Composable
private fun LocalMediaToolbar(
    canRescanAll: Boolean,
    enabled: Boolean,
    addFocusRequester: FocusRequester?,
    onAddSource: () -> Unit,
    onRescanAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        NuvioDialogButton(
            text = stringResource(R.string.local_media_add_source),
            onClick = onAddSource,
            enabled = enabled,
            focusRequester = addFocusRequester,
            // Unweighted buttons stretch to the whole row because the shared button content is a
            // fillMaxSize box, which would make the rescan button vanish to zero width.
            modifier = Modifier.weight(1f),
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp)
                )
            }
        )
        if (canRescanAll) {
            NuvioDialogButton(
                text = stringResource(R.string.local_media_rescan_all),
                onClick = onRescanAll,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAddDevice: () -> Unit,
    onAddNetwork: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.local_media_add_source_title),
        subtitle = stringResource(R.string.local_media_add_source_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            AddSourceOption(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.local_media_add_device_title),
                subtitle = stringResource(R.string.local_media_add_device_subtitle),
                onClick = onAddDevice
            )
            AddSourceOption(
                icon = Icons.Default.Dns,
                title = stringResource(R.string.local_media_add_network_title),
                subtitle = stringResource(R.string.local_media_add_network_subtitle),
                onClick = onAddNetwork
            )
            NuvioDialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AddSourceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.width(24.dp)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.heightIn(min = 4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
internal fun SourceSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
internal fun LocalSourceCard(
    source: LocalSourceEntity,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        onClick = { if (enabled) onClick() },
        onLongClick = { if (enabled) onLongClick() },
        modifier = Modifier
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
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (source.type == LocalSourceType.SMB) {
                        Icons.Default.Dns
                    } else {
                        Icons.Default.Folder
                    },
                    contentDescription = null,
                    tint = if (source.available) {
                        NuvioTheme.colors.TextSecondary
                    } else {
                        NuvioTheme.colors.Primary
                    },
                    modifier = Modifier.width(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = sourceDisplayName(source),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (source.available) {
                            R.string.local_library_folder_available
                        } else {
                            R.string.local_library_folder_offline
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source.available) {
                        NuvioTheme.colors.TextSecondary
                    } else {
                        NuvioTheme.colors.Primary
                    }
                )
            }
            Text(
                text = sourceLocation(source),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sourceDetails(source),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            source.lastError?.let { error ->
                Text(
                    text = stringResource(R.string.local_media_source_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.local_media_press_to_manage),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun sourceDisplayName(source: LocalSourceEntity): String = stringResource(
    if (source.type == LocalSourceType.SMB) {
        R.string.local_source_display_name_smb
    } else {
        R.string.local_source_display_name
    },
    source.name
)

@Composable
private fun sourceDetails(source: LocalSourceEntity): String {
    val itemCount = stringResource(R.string.local_media_item_count, source.itemCount)
    val lastScan = source.lastScanAt?.let { timestamp ->
        stringResource(
            R.string.local_media_last_scan,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
        )
    } ?: stringResource(R.string.local_media_never_scanned)
    return listOf(itemCount, lastScan).joinToString(" · ")
}

private fun sourceLocation(source: LocalSourceEntity): String {
    if (source.type == LocalSourceType.SMB) return source.treeUri
    val decoded = Uri.decode(source.treeUri)
    val tree = decoded.substringAfter("tree/", missingDelimiterValue = "")
    return if (tree.isNotBlank()) {
        tree.substringBefore("/document/").replace(':', '/')
    } else {
        decoded
    }
}

@Composable
fun LocalMediaScreen(onBack: () -> Unit, openSmbOnStart: Boolean = false) {
    BackHandler { onBack() }
    val addFolderFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                onClick = onBack,
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_back))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LocalMediaSettingsContent(
                initialFocusRequester = addFolderFocusRequester,
                openSmbOnStart = openSmbOnStart
            )
        }
    }

    LaunchedEffect(Unit) {
        runCatching { addFolderFocusRequester.requestFocus() }
    }
}
