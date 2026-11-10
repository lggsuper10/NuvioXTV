package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ThemeMode
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun DisplaySettingsContent(
    layoutViewModel: LayoutSettingsViewModel = hiltViewModel(),
    themeViewModel: ThemeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    essentialMode: Boolean = false
) {
    val layoutUiState by layoutViewModel.uiState.collectAsStateWithLifecycle()
    val themeUiState by themeViewModel.uiState.collectAsStateWithLifecycle()
    val streamBadgeUiState by layoutViewModel.streamBadgeUiState.collectAsStateWithLifecycle()
    val customPosterQrState by layoutViewModel.customPosterQrState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sections = visibleLayoutSections(essentialMode = essentialMode)
    var expandedSections by rememberSaveable(essentialMode) {
        mutableStateOf(defaultExpandedSections(essentialMode))
    }
    var showThemeModeDialog by rememberSaveable { mutableStateOf(false) }
    var themeModeDialogOpened by remember { mutableStateOf(false) }
    val themeModeFocusRequester = remember(initialFocusRequester) {
        initialFocusRequester ?: FocusRequester()
    }
    val customPosterQrFocusRequester = remember { FocusRequester() }
    val streamBadgeQrFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showThemeModeDialog) {
        if (showThemeModeDialog) {
            themeModeDialogOpened = true
        } else if (themeModeDialogOpened) {
            themeModeFocusRequester.requestFocusAfterFrames()
        }
    }

    LaunchedEffect(streamBadgeUiState.serverError) {
        val error = streamBadgeUiState.serverError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ThemeSettingsContent(
            viewModel = themeViewModel,
            showHeader = false,
            leadingContent = {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_layout_and_appearance),
                    subtitle = stringResource(R.string.settings_layout_and_appearance_subtitle)
                )

                SettingsSectionLabel(
                    text = stringResource(R.string.settings_layout_and_appearance_appearance),
                    description = stringResource(R.string.settings_layout_and_appearance_appearance_desc)
                )
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsActionRow(
                        title = stringResource(R.string.settings_appearance_mode),
                        subtitle = stringResource(R.string.settings_appearance_mode_desc),
                        value = themeModeLabel(themeUiState.themeMode),
                        onClick = { showThemeModeDialog = true },
                        modifier = Modifier.focusRequester(themeModeFocusRequester)
                    )
                }

                SettingsSectionLabel(
                    text = stringResource(R.string.settings_layout_and_appearance_layout),
                    description = stringResource(R.string.settings_layout_and_appearance_layout_desc)
                )
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    LayoutSettingsSections(
                        sections = sections,
                        uiState = layoutUiState,
                        streamBadgeUiState = streamBadgeUiState,
                        expandedSections = expandedSections,
                        onToggleSection = { section ->
                            expandedSections = if (section.name in expandedSections) {
                                expandedSections - section.name
                            } else {
                                expandedSections + section.name
                            }
                        },
                        viewModel = layoutViewModel,
                        customPosterQrFocusRequester = customPosterQrFocusRequester,
                        streamBadgeQrFocusRequester = streamBadgeQrFocusRequester
                    )
                    if (!essentialMode) {
                        SettingsResetButton(
                            text = stringResource(R.string.layout_reset_appearance),
                            onClick = {
                                layoutViewModel.onEvent(LayoutSettingsEvent.ResetPosterCardStyle)
                                layoutViewModel.onEvent(LayoutSettingsEvent.ResetCardDepthStyle)
                            },
                            modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
                        )
                    }
                }
            }
        )

        if (streamBadgeUiState.isQrModeActive) {
            QrCodeOverlay(
                qrBitmap = streamBadgeUiState.qrCodeBitmap,
                serverUrl = streamBadgeUiState.serverUrl,
                instruction = stringResource(R.string.stream_badge_qr_instruction),
                onClose = {
                    streamBadgeQrFocusRequester.requestFocus()
                    layoutViewModel.stopStreamBadgeQrMode()
                },
                qrSize = 168.dp,
                modifier = Modifier.clip(settingsGroupShape())
            )
        }

        if (customPosterQrState.isActive) {
            QrCodeOverlay(
                qrBitmap = customPosterQrState.qrCodeBitmap,
                serverUrl = customPosterQrState.serverUrl,
                instruction = stringResource(R.string.custom_poster_qr_instruction),
                onClose = {
                    customPosterQrFocusRequester.requestFocus()
                    layoutViewModel.stopCustomPosterQrMode()
                },
                qrSize = 168.dp,
                modifier = Modifier.clip(settingsGroupShape())
            )
        }
    }

    if (showThemeModeDialog) {
        ThemeModeDialog(
            currentMode = themeUiState.themeMode,
            onModeSelected = { mode ->
                themeViewModel.onEvent(ThemeSettingsEvent.SelectThemeMode(mode))
                showThemeModeDialog = false
            },
            onDismiss = { showThemeModeDialog = false }
        )
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.DARK -> R.string.theme_mode_dark
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.SYSTEM -> R.string.theme_mode_system
    }
)

@Composable
private fun ThemeModeDialog(
    currentMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            ThemeMode.DARK,
            stringResource(R.string.theme_mode_dark),
            stringResource(R.string.theme_mode_dark_desc)
        ),
        SettingsPickerOption(
            ThemeMode.LIGHT,
            stringResource(R.string.theme_mode_light),
            stringResource(R.string.theme_mode_light_desc)
        ),
        SettingsPickerOption(
            ThemeMode.SYSTEM,
            stringResource(R.string.theme_mode_system),
            stringResource(R.string.theme_mode_system_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.settings_appearance_mode),
        subtitle = stringResource(R.string.settings_appearance_mode_desc),
        options = options,
        selectedValue = currentMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 520.dp,
        maxHeight = 380.dp
    )
}
