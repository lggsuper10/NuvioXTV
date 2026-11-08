package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayManualOverrideDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    showPlayManually: Boolean = true,
    onPlayManually: () -> Unit,
    showStartFromBeginning: Boolean = false,
    onStartFromBeginning: () -> Unit = {}
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle
    ) {
        if (showPlayManually) {
            NuvioDialogButton(
                text = stringResource(R.string.play_manually),
                onClick = onPlayManually,
                requestInitialFocus = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (showStartFromBeginning) {
            NuvioDialogButton(
                text = stringResource(R.string.cw_action_start_from_beginning),
                onClick = onStartFromBeginning,
                requestInitialFocus = !showPlayManually,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
