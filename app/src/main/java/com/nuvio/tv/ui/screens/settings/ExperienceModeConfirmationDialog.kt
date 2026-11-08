package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogConfirmationButtons

@Composable
internal fun ExperienceModeConfirmationDialog(
    targetMode: ExperienceMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEssential = targetMode == ExperienceMode.ESSENTIAL

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(
            if (isEssential) {
                R.string.experience_mode_confirm_essential_title
            } else {
                R.string.experience_mode_confirm_advanced_title
            }
        ),
        subtitle = if (isEssential) {
            stringResource(R.string.experience_mode_confirm_essential_subtitle)
        } else {
            stringResource(R.string.experience_mode_confirm_advanced_subtitle)
        },
        suppressFirstKeyUp = false
    ) {
        NuvioDialogConfirmationButtons(
            confirmLabel = stringResource(
                if (isEssential) {
                    R.string.experience_mode_switch_to_essential
                } else {
                    R.string.experience_mode_switch_to_advanced
                }
            ),
            onConfirm = {
                onConfirm()
                onDismiss()
            },
            onDismiss = onDismiss
        )
    }
}
