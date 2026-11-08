package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioDialog(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    width: Dp = 520.dp,
    titleTextAlign: TextAlign = TextAlign.Start,
    subtitleTextAlign: TextAlign = TextAlign.Start,
    suppressFirstKeyUp: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    containerBrush: Brush? = null,
    containerBorderColor: Color? = null,
    containerBorderWidth: Dp = NuvioTheme.spacing.hairline,
    containerCornerRadius: Dp = NuvioTheme.radii.xl,
    contentPadding: Dp = NuvioTheme.spacing.xl,
    contentSpacing: Dp = NuvioTheme.spacing.lg,
    backgroundContent: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    var isReady by remember { mutableStateOf(!suppressFirstKeyUp) }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - NuvioTheme.spacing.xxxl).coerceAtLeast(320.dp)
    val containerShape = RoundedCornerShape(containerCornerRadius)
    val backgroundModifier = if (containerBrush == null) {
        Modifier.background(NuvioTheme.colors.BackgroundElevated, containerShape)
    } else {
        Modifier.background(containerBrush, containerShape)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = usePlatformDefaultWidth)
    ) {
        AmbientDialogEffect(onDismiss)
        Box(
            modifier = Modifier
                .width(width)
                .heightIn(max = maxDialogHeight)
                .clip(containerShape)
                .then(backgroundModifier)
                .border(
                    containerBorderWidth,
                    containerBorderColor ?: NuvioTheme.colors.Border,
                    containerShape
                )
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            isReady = true
                        }
                        if (!isReady) {
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            backgroundContent()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(contentSpacing)
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            textDirection = title.contentTextDirection()
                        ),
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = titleTextAlign,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = subtitleTextAlign
                    )
                }

                content()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioDialogConfirmationButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.action_cancel),
    initiallyFocusConfirm: Boolean = false,
    enabled: Boolean = true,
    dismissFocusRequester: FocusRequester? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NuvioDialogButton(
            text = dismissLabel,
            onClick = onDismiss,
            enabled = enabled,
            requestInitialFocus = !initiallyFocusConfirm && dismissFocusRequester == null,
            focusRequester = dismissFocusRequester,
            modifier = Modifier.weight(1f)
        )
        NuvioDialogButton(
            text = confirmLabel,
            onClick = onConfirm,
            enabled = enabled,
            requestInitialFocus = initiallyFocusConfirm,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    containerColor: Color = if (selected) {
        NuvioTheme.colors.Secondary.copy(alpha = 0.18f)
    } else {
        NuvioTheme.colors.BackgroundCard
    },
    contentColor: Color = if (selected) {
        NuvioTheme.colors.Secondary
    } else {
        NuvioTheme.colors.TextPrimary
    },
    leadingContent: (@Composable () -> Unit)? = null
) {
    val internalFocusRequester = remember { FocusRequester() }
    val effectiveFocusRequester = focusRequester ?: internalFocusRequester

    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            runCatching { effectiveFocusRequester.requestFocus() }
        }
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(height)
            .then(
                if (requestInitialFocus || focusRequester != null) {
                    Modifier.focusRequester(effectiveFocusRequester)
                } else {
                    Modifier
                }
            ),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = NuvioTheme.colors.OnSecondary,
            disabledContainerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.5f),
            disabledContentColor = NuvioTheme.colors.TextTertiary
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (leadingContent != null) {
                    leadingContent()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
