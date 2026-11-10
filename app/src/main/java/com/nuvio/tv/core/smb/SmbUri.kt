package com.nuvio.tv.core.smb

import android.net.Uri
import java.util.Locale

/** A server address plus the account to sign in with; no share is required. */
data class SmbServerConfig(
    val host: String,
    val port: Int = SmbUri.DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val displayName: String = ""
)

data class SmbTarget(
    val host: String,
    val port: Int,
    val share: String,
    val path: String
) {
    val sourceUri: String get() = uriFor(path)

    val connectionKey: String
        get() = "${host.lowercase(Locale.US)}:$port/${share.lowercase(Locale.US)}"

    fun uriFor(relativePath: String): String = SmbUri.build(host, port, share, relativePath)

    /** True when [relativePath] is this target's path or lives below it. */
    fun contains(relativePath: String): Boolean = SmbUri.isAtOrBelow(relativePath, path)
}

data class SmbAddressParts(
    val host: String,
    val port: Int = SmbUri.DEFAULT_PORT,
    val share: String? = null,
    val path: String = ""
)

object SmbUri {
    const val DEFAULT_PORT = 445
    const val SCHEME = "smb"

    fun parse(rawUri: String?): SmbTarget? {
        val value = rawUri?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        val segments = runCatching { uri.pathSegments }.getOrDefault(emptyList())
        val share = segments.firstOrNull()?.trim().orEmpty()
        if (share.isBlank()) return null
        val path = normalizePath(segments.drop(1).joinToString("/"))
        return SmbTarget(host = host, port = port, share = share, path = path)
    }

    fun parseServerAddress(value: String): Pair<String, Int>? {
        var server = value.trim().replace('\\', '/')
        server = when {
            server.startsWith("smb://", ignoreCase = true) -> server.substring(6)
            server.startsWith("smb:/", ignoreCase = true) -> server.substring(5)
            server.startsWith("//") -> server.substring(2)
            else -> server
        }
        server = server.substringBefore('/').trim()
        if (server.isBlank()) return null

        if (server.startsWith("[")) {
            val closing = server.indexOf(']')
            if (closing <= 1) return null
            val host = server.substring(1, closing)
            val port = server.substring(closing + 1)
                .removePrefix(":")
                .takeIf { it.isNotBlank() }
                ?.toIntOrNull()
                ?: DEFAULT_PORT
            return host.takeIf { it.isNotBlank() }?.let { it to port }
        }

        val colon = server.lastIndexOf(':')
        if (colon > 0 && server.indexOf(':') == colon) {
            val host = server.substring(0, colon).trim()
            val port = server.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            return host to port
        }
        return server.takeIf { it.isNotBlank() && it != "." && it != ".." }?.let { it to DEFAULT_PORT }
    }

    /**
     * Parses a server field that may also contain a complete SMB address, for example
     * `192.168.1.10/Videos/Movies`, `\\nas\Videos` or `smb://nas/Videos/Movies`.
     * A missing share is allowed here so the dialog can still validate it separately.
     */
    fun parseAddressInput(value: String): SmbAddressParts? {
        val raw = value.trim()
        if (raw.isEmpty()) return null

        val normalized = raw.replace('\\', '/').let { address ->
            when {
                address.startsWith("smb://", ignoreCase = true) -> address
                address.startsWith("smb:/", ignoreCase = true) ->
                    "smb://" + address.substring(5).trimStart('/')
                address.startsWith("smb:", ignoreCase = true) ->
                    "smb://" + address.substring(4).trimStart('/')
                address.startsWith("//") -> "smb://" + address.substring(2)
                else -> "smb://$address"
            }
        }

        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        if (port !in 1..65535) return null

        val segments = runCatching { uri.pathSegments }.getOrDefault(emptyList())
        val share = segments.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val path = normalizePath(segments.drop(1).joinToString("/"))
        return SmbAddressParts(host = host, port = port, share = share, path = path)
    }

    fun createTarget(host: String, port: Int, share: String, path: String): SmbTarget? {
        val cleanHost = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
        val cleanShare = share.trim().trim('/', '\\')
        if (cleanHost.isBlank() || cleanShare.isBlank()) return null
        if (cleanShare.contains('/') || cleanShare.contains('\\')) return null
        if (port !in 1..65535) return null
        return SmbTarget(
            host = cleanHost,
            port = port,
            share = cleanShare,
            path = normalizePath(path)
        )
    }

    fun build(host: String, port: Int, share: String, path: String): String {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(if (port == DEFAULT_PORT) host else "$host:$port")
            .appendPath(share.trim().trim('/', '\\'))
        normalizePath(path)
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach(builder::appendPath)
        return builder.build().toString()
    }

    fun normalizePath(path: String): String = path.trim()
        .replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")

    fun isAtOrBelow(path: String, root: String): Boolean {
        val cleanPath = normalizePath(path)
        val cleanRoot = normalizePath(root)
        return cleanRoot.isEmpty() || cleanPath == cleanRoot || cleanPath.startsWith("$cleanRoot/")
    }
}
