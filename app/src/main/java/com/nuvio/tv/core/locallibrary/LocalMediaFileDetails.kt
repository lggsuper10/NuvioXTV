package com.nuvio.tv.core.locallibrary

import android.net.Uri
import android.provider.DocumentsContract
import com.nuvio.tv.core.smb.SmbUri
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Turns the raw storage URIs the local library stores into the three facts the detail page shows
 * at the bottom: file size, file name and where the file lives.
 *
 * The URIs themselves (`content://…/document/primary%3AMovies%2Fx.mkv`, `file:///storage/…`,
 * `smb://host/share/…`) are never fit to show, so the folder is decoded into the shape a person
 * recognises: a volume-relative path for on-device storage and a UNC path for SMB shares. SMB
 * targets carry no credentials, so nothing secret can leak through here.
 */
object LocalMediaFileDetails {

    private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
    private const val STORAGE_ROOT = "/storage/"
    private const val PRIMARY_VOLUME_ID = "primary"

    /**
     * Folder holding [fileUri], for example `内部存储/Movies/DC` or `\\nas\media\movies\DC`.
     * Returns null when the URI cannot be understood, so callers can fall back to the source name.
     */
    fun folder(fileUri: String?, primaryVolumeLabel: String): String? {
        val uri = fileUri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        return when (uri.scheme?.lowercase()) {
            "smb" -> smbFolder(uri.toString())
            "file" -> fileFolder(uri.path, primaryVolumeLabel)
            "content" -> documentFolder(uri, primaryVolumeLabel)
            else -> null
        }
    }

    /** `46.2 GB` / `812 MB`, or null when the size was never recorded. */
    fun formatSize(
        bytes: Long,
        gbTemplate: String,
        mbTemplate: String
    ): String? {
        if (bytes <= 0L) return null
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            val rounded = round(gib * 10.0) / 10.0
            gbTemplate.format(formatDecimal(rounded))
        } else {
            val mib = max(1L, round(bytes / (1024.0 * 1024.0)).toLong())
            mbTemplate.format(mib.toString())
        }
    }

    /**
     * Trims long names from the middle so the extension and the beginning of the title both
     * survive; a plain tail ellipsis would hide the only part that identifies the format.
     */
    fun shortenFileName(name: String, maxChars: Int = 64): String {
        if (name.length <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 8) name.substring(dot) else ""
        val headLength = (maxChars - extension.length - 1).coerceAtLeast(1)
        return name.take(headLength) + "…" + extension
    }

    private fun formatDecimal(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else (tenths / 10.0).toString()
    }

    private fun smbFolder(rawUri: String): String? {
        val target = SmbUri.parse(rawUri) ?: return null
        val folder = target.path.substringBeforeLast('/', "")
        return buildString {
            append("\\\\")
            append(target.host)
            append('\\')
            append(target.share)
            if (folder.isNotEmpty()) {
                append('\\')
                append(folder.replace('/', '\\'))
            }
        }
    }

    private fun fileFolder(path: String?, primaryVolumeLabel: String): String? {
        val folder = path
            ?.substringBeforeLast('/', "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return withVolumeLabel(folder, primaryVolumeLabel)
    }

    private fun documentFolder(uri: Uri, primaryVolumeLabel: String): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val separator = documentId.indexOf(':')
        val volumeId = if (separator > 0) documentId.substring(0, separator) else null
        val relative = if (separator >= 0) documentId.substring(separator + 1) else documentId
        val folder = relative.substringBeforeLast('/', "").trim('/')
        val volumeLabel = when {
            volumeId == null -> primaryVolumeLabel
            volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true) -> primaryVolumeLabel
            else -> volumeId
        }
        return if (folder.isEmpty()) volumeLabel else "$volumeLabel/$folder"
    }

    /** Maps absolute on-device paths onto the volume label the user knows. */
    private fun withVolumeLabel(folder: String, primaryVolumeLabel: String): String {
        if (folder == PRIMARY_STORAGE_ROOT) return primaryVolumeLabel
        if (folder.startsWith("$PRIMARY_STORAGE_ROOT/")) {
            return "$primaryVolumeLabel/${folder.removePrefix("$PRIMARY_STORAGE_ROOT/")}"
        }
        if (folder.startsWith(STORAGE_ROOT)) {
            // Removable volume: "/storage/1A2B-3C4D/Movies" -> "1A2B-3C4D/Movies".
            return folder.removePrefix(STORAGE_ROOT)
        }
        return folder
    }
}
