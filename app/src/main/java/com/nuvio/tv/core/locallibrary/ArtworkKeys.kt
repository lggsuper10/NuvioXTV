package com.nuvio.tv.core.locallibrary

import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Stable key for locally stored artwork.
 *
 * The old key was the full file:// URI, which changes when a removable volume
 * is remounted under a different path. This key only depends on data that
 * survives a remount: the path relative to the storage root, file size and
 * last-modified time.
 */
object ArtworkKeys {
    fun forFileUri(fileUri: String): String {
        val path = runCatching { Uri.parse(fileUri).path }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fileUri
        val file = File(path)
        val usable = file.isFile
        val relative = relativePath(path)
        val size = if (usable) file.length() else 0L
        val modified = if (usable) file.lastModified() else 0L
        return UUID.nameUUIDFromBytes("$relative|$size|$modified".toByteArray()).toString()
    }

    fun legacyForFileUri(fileUri: String): String =
        UUID.nameUUIDFromBytes(fileUri.toByteArray()).toString()

    private fun relativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        val relative = if (normalized.startsWith("/storage/")) {
            normalized.removePrefix("/storage/").substringAfter('/', "")
        } else {
            normalized.trimStart('/')
        }
        return relative.lowercase(Locale.ROOT)
    }
}
