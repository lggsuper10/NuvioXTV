package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentCacheClearResult
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaybackSettingsScreen(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.playback_title),
        subtitle = stringResource(R.string.playback_subtitle)
    ) {
        PlaybackSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun PlaybackSettingsContent(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    embedded: Boolean = false
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val transparentLetterbox by viewModel.transparentLetterbox.collectAsStateWithLifecycle(initialValue = false)
    val torrentSettings by viewModel.torrentSettingsFlow.collectAsStateWithLifecycle(initialValue = TorrentSettingsData())
    val torrentCacheState by viewModel.torrentCacheState.collectAsStateWithLifecycle()
    val torrentState by viewModel.torrentState.collectAsStateWithLifecycle()
    var torrentCacheClearResult by remember { mutableStateOf<TorrentCacheClearResult?>(null) }
    var torrentCacheClearFailed by remember { mutableStateOf(false) }
    val torrentCacheClearAvailable = torrentState !is TorrentState.Connecting &&
        torrentState !is TorrentState.Streaming &&
        !torrentCacheState.isClearing
    val p2p = P2pSettingsUi(
        enabled = torrentSettings.p2pEnabled,
        hideStats = torrentSettings.hideTorrentStats,
        profile = torrentSettings.torrentProfile,
        cacheSize = torrentSettings.cacheSize,
        cacheSummary = torrentCacheSummary(
            cacheState = torrentCacheState,
            clearAvailable = torrentCacheClearAvailable,
            clearResult = torrentCacheClearResult,
            clearFailed = torrentCacheClearFailed
        ),
        cacheClearEnabled = torrentCacheClearAvailable
    )
    val installedAddonNames by viewModel.installedAddonNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledPluginNames by viewModel.enabledPluginNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var openDialog by remember { mutableStateOf<PlaybackDialog?>(null) }
    var memoryUsageTrigger by remember { mutableIntStateOf(0) }
    var showMemoryUsage by remember { mutableStateOf(false) }
    val onUpdate: PlaybackSettingsUpdate = remember(viewModel, coroutineScope) {
        { block -> coroutineScope.launch { viewModel.block() } }
    }

    LaunchedEffect(memoryUsageTrigger) {
        if (memoryUsageTrigger == 0) return@LaunchedEffect
        showMemoryUsage = true
        delay(2200)
        showMemoryUsage = false
    }

    Column(
        modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!embedded) {
            SettingsDetailHeader(
                title = stringResource(R.string.playback_title),
                subtitle = stringResource(R.string.playback_subtitle)
            )
        }

        val sections: @Composable () -> Unit = {
            PlaybackSettingsSections(
                playerSettings = playerSettings,
                p2p = p2p,
                transparentLetterbox = transparentLetterbox,
                onUpdate = onUpdate,
                onOpenDialog = { openDialog = it },
                onMemorySettingChanged = { memoryUsageTrigger++ },
                onClearTorrentCache = {
                    torrentCacheClearResult = null
                    torrentCacheClearFailed = false
                    coroutineScope.launch {
                        runCatching { viewModel.clearTorrentCache() }
                            .onSuccess { torrentCacheClearResult = it }
                            .onFailure { torrentCacheClearFailed = true }
                    }
                },
                initialFocusRequester = initialFocusRequester,
                scrollable = !embedded
            )
        }

        if (embedded) {
            sections()
        } else {
            SettingsGroupCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                sections()
            }
        }

        AnimatedVisibility(
            visible = showMemoryUsage && (
                playerSettings.bufferEngineEnabled ||
                    playerSettings.parallelNetworkEnabled ||
                    playerSettings.nuvioPerformanceModeEnabled
                ),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PlaybackMemoryUsageCard(settings = playerSettings)
        }
    }

    PlaybackSettingsDialogs(
        dialog = openDialog,
        settings = playerSettings,
        p2p = p2p,
        installedAddonNames = installedAddonNames,
        enabledPluginNames = enabledPluginNames,
        onUpdate = onUpdate,
        onDismiss = { openDialog = null }
    )
}

/**
 * The single "播放设置" pane: playback, integrations and tracking stacked in one scrolling
 * page, each introduced by its own label. The three content composables run in embedded mode so
 * they emit plain columns instead of scroll containers of their own.
 */
@Composable
internal fun MergedPlaybackSettingsContent(
    isEssentialMode: Boolean,
    initialFocusRequester: FocusRequester? = null
) {
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.playback_title),
            subtitle = stringResource(R.string.playback_subtitle)
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "merged_playback_label") {
                    SettingsSectionLabel(text = stringResource(R.string.settings_playback))
                }
                item(key = "merged_playback_content") {
                    if (isEssentialMode) {
                        EssentialPlaybackSettingsContent(
                            initialFocusRequester = initialFocusRequester,
                            embedded = true
                        )
                    } else {
                        PlaybackSettingsContent(
                            initialFocusRequester = initialFocusRequester,
                            embedded = true
                        )
                    }
                }
                item(key = "merged_integration_label") {
                    SettingsSectionLabel(text = stringResource(R.string.settings_integration))
                }
                item(key = "merged_integration_content") {
                    TmdbSettingsContent(embedded = true)
                }
                item(key = "merged_tracking_label") {
                    SettingsSectionLabel(text = stringResource(R.string.settings_tracking_title))
                }
                item(key = "merged_tracking_content") {
                    TrackingSettingsContent(embedded = true)
                }
            }
            SettingsVerticalScrollIndicators(state = listState)
        }
    }
}
