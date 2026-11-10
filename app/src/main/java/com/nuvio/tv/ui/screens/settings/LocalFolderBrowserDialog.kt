@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.locallibrary.LocalStorageAccess
import com.nuvio.tv.core.locallibrary.LocalStorageRoot
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioDialogButton
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LocalFolderBrowserDialog(
    roots: List<LocalStorageRoot>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val rootFocusRequester = remember { FocusRequester() }
    val firstDirectoryFocusRequester = remember { FocusRequester() }
    val selectFocusRequester = remember { FocusRequester() }
    val currentDirectory = currentPath?.let(::File)
    val isStorageRoot = roots.any { it.path == currentPath }
    val parentPath = if (currentPath == null || isStorageRoot) {
        null
    } else {
        currentDirectory?.parentFile?.absolutePath
    }

    LaunchedEffect(currentPath) {
        val path = currentPath
        if (path == null) {
            directories = emptyList()
            loadError = null
            isLoading = false
            rootFocusRequester.requestFocusAfterFrames()
            return@LaunchedEffect
        }
        isLoading = true
        loadError = null
        directories = emptyList()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(path)
                val children = directory.listFiles()
                    ?: throw IllegalStateException(directory.absolutePath)
                children.asSequence()
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    .toList()
            }
        }
        result.onSuccess { directories = it }
            .onFailure { loadError = it.message ?: it.javaClass.simpleName }
        isLoading = false
    }

    LaunchedEffect(currentPath, directories.size, loadError) {
        if (currentPath == null) return@LaunchedEffect
        if (directories.isNotEmpty()) {
            firstDirectoryFocusRequester.requestFocusAfterFrames()
        } else {
            selectFocusRequester.requestFocusAfterFrames()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.local_media_browser_title),
        subtitle = stringResource(R.string.local_media_browser_subtitle),
        width = 880.dp,
        suppressFirstKeyUp = false,
        contentPadding = 18.dp,
        contentSpacing = 10.dp
    ) {
        Text(
            text = currentPath ?: stringResource(R.string.local_media_browser_storage_roots),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        if (currentPath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NuvioDialogButton(
                    text = stringResource(
                        if (isStorageRoot) {
                            R.string.local_media_browser_storage_roots
                        } else {
                            R.string.local_media_browser_up
                        }
                    ),
                    onClick = {
                        currentPath = if (isStorageRoot) null else parentPath
                    },
                    // NuvioDialogButton's content is a fillMaxSize box, so an unweighted button in
                    // a Row claims the whole row and leaves a weighted sibling at zero width.
                    // Both buttons therefore carry a weight.
                    modifier = Modifier.weight(1f),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.heightIn(max = 18.dp)
                        )
                    }
                )
                NuvioDialogButton(
                    text = stringResource(R.string.local_media_browser_select_current),
                    onClick = {
                        currentPath?.let { onSelect(LocalStorageAccess.fileTreeUri(it)) }
                    },
                    focusRequester = selectFocusRequester,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        when {
            isLoading -> {
                BrowserStatusText(stringResource(R.string.local_media_browser_loading))
            }

            loadError != null -> {
                BrowserStatusText(
                    stringResource(R.string.local_media_browser_read_failed, loadError.orEmpty())
                )
            }

            currentPath == null && roots.isEmpty() -> {
                BrowserStatusText(stringResource(R.string.local_media_browser_no_storage))
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (currentPath == null) {
                        itemsIndexed(roots, key = { _, root -> root.path }) { index, root ->
                            SettingsActionRow(
                                title = stringResource(
                                    if (root.isPrimary) {
                                        R.string.local_media_storage_internal
                                    } else {
                                        R.string.local_media_storage_external
                                    }
                                ),
                                subtitle = rootSubtitle(root),
                                onClick = { currentPath = root.path },
                                leadingIcon = Icons.Default.Folder,
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(rootFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    } else {
                        if (directories.isEmpty()) {
                            item(key = "local_browser_empty") {
                                BrowserStatusText(
                                    stringResource(R.string.local_media_browser_empty)
                                )
                            }
                        }
                        itemsIndexed(
                            directories,
                            key = { _, directory -> directory.absolutePath }
                        ) { index, directory ->
                            SettingsActionRow(
                                title = directory.name,
                                subtitle = directory.absolutePath,
                                onClick = { currentPath = directory.absolutePath },
                                leadingIcon = Icons.Default.Folder,
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstDirectoryFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserStatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

private fun rootSubtitle(root: LocalStorageRoot): String {
    return listOfNotNull(
        root.description?.takeIf { it.isNotBlank() && it != root.path },
        root.path
    ).joinToString("  ·  ")
}
