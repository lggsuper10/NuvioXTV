@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogConfirmationButtons

@Composable
internal fun AccountSignOutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.account_sign_out_confirm_title),
        subtitle = stringResource(R.string.account_sign_out_confirm_subtitle),
        suppressFirstKeyUp = false
    ) {
        NuvioDialogConfirmationButtons(
            confirmLabel = stringResource(R.string.account_sign_out),
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    }
}
