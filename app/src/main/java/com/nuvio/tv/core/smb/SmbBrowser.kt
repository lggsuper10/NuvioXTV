package com.nuvio.tv.core.smb

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.IOException
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One folder inside a share, with its path relative to the share root. */
data class SmbFolder(val name: String, val path: String)

/**
 * Browsing support for the "add server, then pick a folder" flow.
 *
 * SMBJ (used for playback) cannot enumerate the shares a server offers, so share listing and
 * folder browsing go through jcifs-ng. Playback keeps using [SmbConnectionManager]/SMBJ.
 */
@Singleton
class SmbBrowser @Inject constructor() {
    private val lock = Any()
    private val contexts = HashMap<BrowserKey, CIFSContext>()

    suspend fun listShares(
        host: String,
        port: Int,
        credentials: SmbCredentials
    ): List<String> = withContext(Dispatchers.IO) {
        withClient(host, port, credentials) { context ->
            val root = SmbFile(serverUrl(host, port), context)
            root.listFiles()
                .mapNotNull { file ->
                    val name = file.name?.trim()?.trimEnd('/')?.trim()
                    if (name.isNullOrBlank() || name.endsWith("$") || name.startsWith(".")) {
                        return@mapNotNull null
                    }
                    if (!runCatching { file.isDirectory }.getOrDefault(false)) return@mapNotNull null
                    name
                }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    suspend fun listFolders(
        host: String,
        port: Int,
        share: String,
        path: String,
        credentials: SmbCredentials
    ): List<SmbFolder> = withContext(Dispatchers.IO) {
        withClient(host, port, credentials) { context ->
            val directory = SmbFile(directoryUrl(host, port, share, path), context)
            val parent = SmbUri.normalizePath(path)
            directory.listFiles()
                .mapNotNull { file ->
                    val name = file.name?.trim()?.trimEnd('/')?.trim()
                    if (name.isNullOrBlank() || name.startsWith(".")) return@mapNotNull null
                    if (!runCatching { file.isDirectory }.getOrDefault(false)) return@mapNotNull null
                    SmbFolder(
                        name = name,
                        path = if (parent.isEmpty()) name else "$parent/$name"
                    )
                }
                .distinctBy { it.name.lowercase(Locale.US) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    /** Drops pooled connections; call after an account change so the next browse re-authenticates. */
    fun invalidate() {
        val stale = synchronized(lock) {
            val values = contexts.values.toList()
            contexts.clear()
            values
        }
        stale.forEach { context -> runCatching { context.close() } }
    }

    private fun <T> withClient(
        host: String,
        port: Int,
        credentials: SmbCredentials,
        block: (CIFSContext) -> T
    ): T {
        val key = BrowserKey(
            host = host.lowercase(Locale.US),
            port = port,
            username = credentials.username?.lowercase(Locale.US),
            domain = credentials.domain?.lowercase(Locale.US),
            passwordFingerprint = credentials.password.takeIf { it.isNotEmpty() }?.hashCode()
        )
        val first = contextFor(key, credentials)
        return try {
            block(first)
        } catch (firstError: Throwable) {
            drop(key)
            // A stale pooled connection is common after the TV sleeps; retry once with a fresh one.
            try {
                block(contextFor(key, credentials))
            } catch (retryError: Throwable) {
                runCatching { drop(key) }
                retryError.addSuppressed(firstError)
                Log.w(TAG, "SMB browse failed for $host:$port", retryError)
                throw IOException(
                    "Could not browse $host:$port: " +
                        (retryError.message ?: retryError.javaClass.simpleName),
                    retryError
                )
            }
        }
    }

    private fun contextFor(key: BrowserKey, credentials: SmbCredentials): CIFSContext =
        synchronized(lock) {
            contexts[key] ?: createContext(credentials).also { contexts[key] = it }
        }

    private fun drop(key: BrowserKey) {
        val stale = synchronized(lock) { contexts.remove(key) }
        stale?.let { context -> runCatching { context.close() } }
    }

    private fun createContext(credentials: SmbCredentials): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.disableSMB1", "true")
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.netbios.cachePolicy", "0")
            setProperty("jcifs.smb.client.allowGuestFallback", "true")
        }
        val base = BaseContext(PropertyConfiguration(properties))
        val context = if (credentials.username.isNullOrBlank() && credentials.password.isEmpty()) {
            base.withGuestCrendentials()
        } else {
            base.withCredentials(
                NtlmPasswordAuthenticator(
                    credentials.domain.orEmpty(),
                    credentials.username.orEmpty(),
                    credentials.password
                )
            )
        }
        return BrowserContext(base, context)
    }

    private fun serverUrl(host: String, port: Int): String = "smb://${authority(host, port)}/"

    private fun directoryUrl(host: String, port: Int, share: String, path: String): String {
        val segments = ArrayList<String>()
        share.trim().trim('/', '\\').takeIf { it.isNotEmpty() }?.let(segments::add)
        SmbUri.normalizePath(path).split('/').filter { it.isNotEmpty() }.forEach(segments::add)
        if (segments.isEmpty()) return serverUrl(host, port)
        // jcifs-ng does NOT percent-decode the URL path: it hands the raw chars to the server.
        // Encoding here would make "共享文件" look like "%E5%85%B1..." on the wire and fail the
        // tree connect with STATUS_BAD_NETWORK_NAME, so the names are kept verbatim.
        return "smb://${authority(host, port)}/" + segments.joinToString("/") + "/"
    }

    private fun authority(host: String, port: Int): String {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        val hostPart = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
        return if (port == SmbUri.DEFAULT_PORT) hostPart else "$hostPart:$port"
    }

    private data class BrowserKey(
        val host: String,
        val port: Int,
        val username: String?,
        val domain: String?,
        val passwordFingerprint: Int?
    )

    /** Keeps the base context alive: closing the credential wrapper also closes the base. */
    private class BrowserContext(
        private val base: BaseContext,
        private val delegate: CIFSContext
    ) : CIFSContext by delegate {
        override fun close(): Boolean {
            val closed = runCatching { delegate.close() }.getOrDefault(false)
            runCatching { base.close() }
            return closed
        }
    }

    private companion object {
        const val TAG = "SmbBrowser"
    }
}
