package com.nuvio.tv.core.locallibrary

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

data class LocalStorageRoot(
    val path: String,
    val description: String?,
    val isPrimary: Boolean
)

object LocalStorageAccess {
    fun roots(context: Context): List<LocalStorageRoot> {
        val roots = LinkedHashMap<String, LocalStorageRoot>()

        fun addRoot(path: String?, description: String?, isPrimary: Boolean) {
            val directory = path?.let(::File) ?: return
            if (!directory.isDirectory) return
            val normalizedPath = directory.absolutePath
            val existing = roots[normalizedPath]
            if (existing == null || (!existing.isPrimary && isPrimary)) {
                roots[normalizedPath] = LocalStorageRoot(
                    path = normalizedPath,
                    description = description?.takeIf { it.isNotBlank() },
                    isPrimary = isPrimary
                )
            }
        }

        runCatching {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager?.storageVolumes?.forEach { volume ->
                if (volume.state != Environment.MEDIA_MOUNTED) return@forEach
                val description = runCatching { volume.getDescription(context) }.getOrNull()
                addRoot(volume.directory?.absolutePath, description, volume.isPrimary)
            }
        }

        runCatching {
            addRoot(
                Environment.getExternalStorageDirectory()?.absolutePath,
                null,
                true
            )
        }

        runCatching {
            File("/storage").listFiles()?.forEach { file ->
                if (file.isDirectory && file.name != "emulated" && file.name != "self") {
                    addRoot(file.absolutePath, null, false)
                }
            }
        }

        return roots.values.sortedWith(
            compareByDescending<LocalStorageRoot> { it.isPrimary }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path }
        )
    }

    fun canBrowseFiles(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Files behind a file:// local source can only be deleted with WRITE_EXTERNAL_STORAGE on
     * Android 10 and below. Android 11+ uses all-files access instead of the legacy permission.
     */
    fun canWriteLocalFiles(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Runtime permissions the built-in file browser needs on this device. Android 11+ gates the
     * browser behind all-files access instead, so there is nothing to request at runtime there.
     */
    fun runtimePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        emptyArray()
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val appSettingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        val packageManager = context.packageManager
        return if (appSettingsIntent.resolveActivity(packageManager) != null) {
            appSettingsIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun fileTreeUri(path: String): String = Uri.fromFile(File(path)).toString()
}
