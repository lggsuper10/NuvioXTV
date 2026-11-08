@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.AVAILABLE_TMDB_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogButton

@Composable
fun TmdbSettingsScreen(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.tmdb_title),
        subtitle = stringResource(R.string.tmdb_subtitle)
    ) {
        TmdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun TmdbSettingsContent(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    embedded: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    val rows: @Composable () -> Unit = {
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                title = stringResource(R.string.tmdb_enable_title),
                subtitle = stringResource(R.string.tmdb_enable_subtitle),
                checked = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xxs)
                    .then(
                        if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        })
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_modern_home_title),
                subtitle = stringResource(R.string.tmdb_modern_home_subtitle),
                checked = uiState.modernHomeEnabled,
                enabled = uiState.enabled,
                onToggle = {
                    viewModel.onEvent(
                        TmdbSettingsEvent.ToggleModernHomeEnabled(!uiState.modernHomeEnabled)
                    )
                }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_enrich_continue_watching_title),
                subtitle = stringResource(R.string.tmdb_enrich_continue_watching_subtitle),
                checked = uiState.enrichContinueWatching,
                enabled = uiState.enabled,
                onToggle = {
                    viewModel.onEvent(
                        TmdbSettingsEvent.ToggleEnrichContinueWatching(!uiState.enrichContinueWatching)
                    )
                }
            )

            val languageName = AVAILABLE_TMDB_LANGUAGES
                .find { it.code == uiState.language }
                ?.displayName
                ?: uiState.language.uppercase()
            SettingsActionRow(
                title = stringResource(R.string.tmdb_language_title),
                subtitle = stringResource(R.string.tmdb_language_subtitle),
                value = languageName,
                enabled = uiState.enabled,
                onClick = { showLanguageDialog = true }
            )

            val notSet = stringResource(R.string.sub_not_set)
            SettingsActionRow(
                title = stringResource(R.string.tmdb_api_key_title),
                subtitle = stringResource(R.string.tmdb_api_key_subtitle),
                value = maskApiKey(uiState.apiKey, notSet),
                onClick = { showApiKeyDialog = true }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_douban_title),
                subtitle = stringResource(R.string.tmdb_douban_subtitle),
                checked = uiState.useDouban,
                onToggle = {
                    viewModel.onEvent(TmdbSettingsEvent.ToggleDouban(!uiState.useDouban))
                }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_artwork_title),
                subtitle = stringResource(R.string.tmdb_artwork_subtitle),
                checked = uiState.useArtwork,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleArtwork(!uiState.useArtwork)) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_basic_info_title),
                subtitle = stringResource(R.string.tmdb_basic_info_subtitle),
                checked = uiState.useBasicInfo,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleBasicInfo(!uiState.useBasicInfo)) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_details_title),
                subtitle = stringResource(R.string.tmdb_details_subtitle),
                checked = uiState.useDetails,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleDetails(!uiState.useDetails)) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_credits_title),
                subtitle = stringResource(R.string.tmdb_credits_subtitle),
                checked = uiState.useCredits,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleCredits(!uiState.useCredits)) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_productions_title),
                subtitle = stringResource(R.string.tmdb_productions_subtitle),
                checked = uiState.useProductions,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleProductions(!uiState.useProductions)) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_networks_title),
                subtitle = stringResource(R.string.tmdb_networks_subtitle),
                checked = uiState.useNetworks,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleNetworks(!uiState.useNetworks)) }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_episodes_title),
                subtitle = stringResource(R.string.tmdb_episodes_subtitle),
                checked = uiState.useEpisodes,
                enabled = uiState.enabled,
                onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEpisodes(!uiState.useEpisodes)) }
            )

            if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
                SettingsToggleRow(
                    title = stringResource(R.string.tmdb_trailers_title),
                    subtitle = stringResource(R.string.tmdb_trailers_subtitle),
                    checked = uiState.useTrailers,
                    enabled = uiState.enabled,
                    onToggle = {
                        viewModel.onEvent(
                            TmdbSettingsEvent.ToggleTrailers(!uiState.useTrailers)
                        )
                    }
                )
            }

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_more_like_this_title),
                subtitle = stringResource(R.string.tmdb_more_like_this_subtitle),
                checked = uiState.useMoreLikeThis,
                enabled = uiState.enabled,
                onToggle = {
                    viewModel.onEvent(
                        TmdbSettingsEvent.ToggleMoreLikeThis(!uiState.useMoreLikeThis)
                    )
                }
            )

            SettingsToggleRow(
                title = stringResource(R.string.tmdb_collections_title),
                subtitle = stringResource(R.string.tmdb_collections_subtitle),
                checked = uiState.useCollections,
                enabled = uiState.enabled,
                onToggle = {
                    viewModel.onEvent(
                        TmdbSettingsEvent.ToggleCollections(!uiState.useCollections)
                    )
                }
            )
        }
    }

    if (embedded) {
        rows()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.tmdb_title),
                subtitle = stringResource(R.string.tmdb_subtitle)
            )

            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    rows()
                }
                SettingsVerticalScrollIndicators(state = scrollState)
            }
        }
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.tmdb_language_dialog_title),
            selectedLanguage = uiState.language,
            showNoneOption = false,
            onLanguageSelected = { language ->
                viewModel.onEvent(TmdbSettingsEvent.SetLanguage(language ?: "en"))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showApiKeyDialog) {
        TmdbApiKeyDialog(
            currentValue = uiState.apiKey,
            onSave = {
                viewModel.onEvent(TmdbSettingsEvent.SetApiKey(it))
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.onEvent(TmdbSettingsEvent.SetApiKey(""))
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun TmdbApiKeyDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { inputFocusRequester.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tmdb_api_key_dialog_title),
        subtitle = stringResource(R.string.tmdb_api_key_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(
                        NuvioTheme.spacing.hairline,
                        NuvioTheme.colors.Border
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                        textDirection = TextDirection.Content
                    ),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.tmdb_api_key_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NuvioDialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            NuvioDialogButton(
                text = stringResource(R.string.action_clear),
                onClick = onClear,
                modifier = Modifier.weight(1f)
            )
            NuvioDialogButton(
                text = stringResource(R.string.action_save),
                onClick = { onSave(value) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "\\u2022\\u2022\\u2022\\u2022"
    else "\\u2022\\u2022\\u2022\\u2022\\u2022\\u2022${trimmed.takeLast(4)}"
}
