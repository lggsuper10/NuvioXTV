@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.smb.SmbServerConfig
import com.nuvio.tv.core.smb.SmbUri
import com.nuvio.tv.data.locallibrary.SmbServerEntity
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Adds or edits an SMB server. Only the address and account are asked for; the folders that
 * become media sources are picked afterwards from the server's share tree.
 */
@Composable
internal fun SmbServerDialog(
    initialServer: SmbServerEntity? = null,
    serverTestRunning: Boolean,
    serverTestSucceeded: Boolean?,
    serverTestMessage: String?,
    serverTestShares: List<String>,
    isBusy: Boolean,
    onTest: (SmbServerConfig) -> Unit,
    onSave: (SmbServerConfig) -> Unit,
    onDismiss: () -> Unit,
    onClearTest: () -> Unit
) {
    var address by remember(initialServer?.id) {
        mutableStateOf(
            initialServer?.let { server ->
                if (server.port == SmbUri.DEFAULT_PORT) server.host else "${server.host}:${server.port}"
            }.orEmpty()
        )
    }
    var username by remember(initialServer?.id) { mutableStateOf(initialServer?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var domain by remember(initialServer?.id) { mutableStateOf(initialServer?.domain.orEmpty()) }
    var displayName by remember(initialServer?.id) { mutableStateOf(initialServer?.name.orEmpty()) }

    LaunchedEffect(initialServer?.id) { onClearTest() }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val fieldsMaxHeight = (screenHeight - 340.dp).coerceIn(160.dp, 300.dp)
    val parsed = SmbUri.parseServerAddress(address)
    val config = parsed?.let { (host, port) ->
        SmbServerConfig(
            host = host,
            port = port,
            username = username,
            password = password,
            domain = domain,
            displayName = displayName
        )
    }
    val canConnect = config != null && !isBusy
    val canSave = canConnect && !serverTestRunning && serverTestSucceeded == true
    val editing = initialServer != null
    val dismiss = {
        if (!isBusy) {
            onClearTest()
            onDismiss()
        }
    }

    NuvioDialog(
        onDismiss = dismiss,
        title = stringResource(
            if (editing) R.string.local_media_smb_server_edit_title else R.string.local_media_smb_server_title
        ),
        subtitle = stringResource(R.string.local_media_smb_server_subtitle),
        width = 720.dp,
        suppressFirstKeyUp = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = fieldsMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmbField(
                label = stringResource(R.string.local_media_smb_server_address),
                supportingText = stringResource(R.string.local_media_smb_server_address_hint)
            ) {
                InputField(
                    value = address,
                    onValueChange = {
                        onClearTest()
                        address = it
                    },
                    placeholder = stringResource(R.string.local_media_smb_server_placeholder),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SmbField(
                label = stringResource(R.string.local_media_smb_username),
                supportingText = stringResource(R.string.local_media_smb_credentials_hint)
            ) {
                InputField(
                    value = username,
                    onValueChange = {
                        onClearTest()
                        username = it
                    },
                    placeholder = stringResource(R.string.local_media_smb_username_hint),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SmbField(label = stringResource(R.string.local_media_smb_password)) {
                InputField(
                    value = password,
                    onValueChange = {
                        onClearTest()
                        password = it
                    },
                    placeholder = stringResource(
                        if (editing) {
                            R.string.local_media_smb_password_keep_hint
                        } else {
                            R.string.local_media_smb_password_hint
                        }
                    ),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SmbField(label = stringResource(R.string.local_media_smb_domain)) {
                InputField(
                    value = domain,
                    onValueChange = {
                        onClearTest()
                        domain = it
                    },
                    placeholder = stringResource(R.string.local_media_smb_domain_hint),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SmbField(label = stringResource(R.string.local_media_smb_server_name)) {
                InputField(
                    value = displayName,
                    onValueChange = {
                        onClearTest()
                        displayName = it
                    },
                    placeholder = stringResource(R.string.local_media_smb_server_name_hint),
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        serverTestMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (serverTestSucceeded == true) {
                    NuvioTheme.colors.Success
                } else {
                    NuvioTheme.colors.Error
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (serverTestSucceeded == true && serverTestShares.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.local_media_smb_server_shares,
                    serverTestShares.joinToString("、")
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (serverTestSucceeded != true && !serverTestRunning) {
            Text(
                text = stringResource(R.string.local_media_smb_test_required),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.action_cancel),
                onClick = dismiss,
                enabled = !isBusy
            )
            SettingsDialogActionButton(
                modifier = Modifier.weight(1f),
                text = stringResource(
                    if (serverTestRunning) R.string.local_media_smb_testing else R.string.local_media_smb_test
                ),
                onClick = {
                    if (!serverTestRunning) config?.let(onTest)
                },
                enabled = canConnect
            )
            SettingsDialogActionButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.local_media_smb_server_save),
                onClick = { config?.let(onSave) },
                enabled = canSave
            )
        }
    }
}

@Composable
private fun SmbField(
    label: String,
    supportingText: String? = null,
    field: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        field()
        supportingText?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}
