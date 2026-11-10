package com.nuvio.tv.core.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.nuvio.tv.data.locallibrary.LocalSourceDao
import com.nuvio.tv.data.locallibrary.LocalSourceEntity
import com.nuvio.tv.data.locallibrary.LocalSourceType
import com.nuvio.tv.data.locallibrary.SmbServerEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

class SmbRandomAccessFile internal constructor(
    private val reopen: () -> com.hierynomus.smbj.share.File,
    private var file: com.hierynomus.smbj.share.File,
    val length: Long
) : Closeable {
    private val lock = Any()
    private var closed = false

    fun read(buffer: ByteArray, offset: Int, position: Long, length: Int): Int {
        if (length <= 0) return 0
        return synchronized(lock) {
            if (closed) return@synchronized 0
            try {
                file.read(buffer, position, offset, length)
            } catch (first: Exception) {
                // The player keeps the file open while its buffer is full, so the server may have
                // dropped the session (or the box slept) since the last read. Reopen at the same
                // offset and retry once instead of surfacing a load error mid-playback.
                if (closed) throw first
                Log.w(TAG, "SMB read failed at offset $position, reopening the file", first)
                runCatching { file.close() }
                file = try {
                    reopen()
                } catch (reopenError: Exception) {
                    first.addSuppressed(reopenError)
                    throw first
                }
                file.read(buffer, position, offset, length)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { file.close() }
        }
    }

    private companion object {
        const val TAG = "SmbPlayback"
    }
}

@Singleton
class SmbConnectionManager @Inject constructor(
    @ApplicationContext context: android.content.Context,
    private val sourceDao: LocalSourceDao,
    private val credentialStore: SmbCredentialStore
) {
    private val lock = Any()
    private val connections = HashMap<ConnectionKey, ActiveConnection>()

    // Credentials that are known to work for a connection key. Populated by successful
    // listings/connection tests so playback does not depend on re-matching UI state later.
    private val credentialLock = Any()
    private val credentialCache = HashMap<String, MutableList<CachedCredentials>>()

    @Suppress("unused")
    private val appContext = context.applicationContext

    suspend fun list(source: LocalSourceEntity, path: String): List<SmbEntry> =
        withContext(Dispatchers.IO) {
            val target = SmbUri.parse(source.treeUri)
                ?: throw IOException("Invalid SMB source URI")
            val credentials = credentialsFor(source)
            withShare(target, credentials) { share ->
                share.list(toSmbPath(path))
                    .mapNotNull { entry ->
                        val name = entry.fileName
                        if (name.isNullOrBlank() || name == "." || name == "..") return@mapNotNull null
                        val childPath = if (path.isBlank()) name else "${SmbUri.normalizePath(path)}/$name"
                        SmbEntry(
                            name = name,
                            path = childPath,
                            isDirectory = (entry.fileAttributes and
                                FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                            sizeBytes = entry.endOfFile.coerceAtLeast(0L)
                        )
                    }
            }.also { rememberCredentials(target, credentials) }
        }

    /**
     * Verifies a server account without needing a share name. Connecting to IPC$ succeeds for any
     * valid account, and it is the same authentication path playback uses later.
     */
    suspend fun testServerConnection(config: SmbServerConfig): Unit = withContext(Dispatchers.IO) {
        if (config.host.isBlank()) throw IOException("Invalid SMB server address")
        if (config.port !in 1..65535) throw IOException("Invalid SMB port")
        val credentials = credentialsOf(config.username, config.password, config.domain)
        val smbConfig = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(30, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
        val client = SMBClient(smbConfig)
        try {
            val connection: Connection = client.connect(config.host, config.port)
            try {
                val passwordChars = credentials.password.takeIf { it.isNotEmpty() }?.toCharArray()
                val authentication = try {
                    if (credentials.username.isNullOrBlank() && credentials.password.isEmpty()) {
                        AuthenticationContext.guest()
                    } else {
                        AuthenticationContext(
                            credentials.username.orEmpty(),
                            passwordChars ?: CharArray(0),
                            credentials.domain
                        )
                    }
                } finally {
                    passwordChars?.fill('\u0000')
                }
                val session = connection.authenticate(authentication)
                session.connectShare("IPC$")
            } finally {
                runCatching { connection.close() }
            }
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException(
                "SMB authentication failed (${config.host}:${config.port}): " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        } finally {
            runCatching { client.close() }
        }
    }

    fun credentialsFor(server: SmbServerEntity): SmbCredentials = SmbCredentials(
        username = server.username?.trim()?.takeIf { it.isNotEmpty() },
        password = server.credentialRef?.takeIf { it.isNotBlank() }
            ?.let(credentialStore::load).orEmpty(),
        domain = server.domain?.trim()?.takeIf { it.isNotEmpty() }
    )

    private fun credentialsOf(username: String, password: String, domain: String): SmbCredentials =
        SmbCredentials(
            username = username.trim().takeIf { it.isNotEmpty() },
            password = password,
            domain = domain.trim().takeIf { it.isNotEmpty() }
        )

    fun openRead(uri: String): SmbRandomAccessFile {
        val target = SmbUri.parse(uri) ?: throw IOException("Invalid SMB playback URI")
        val credentials = credentialsForPlayback(target)
        if (!credentials.hasCredentials) {
            Log.w(TAG, "No SMB credentials matched $uri, authenticating as guest")
        }
        val file = openFileHandle(target, credentials)
        val length = try {
            file.getFileInformation(FileStandardInformation::class.java)
                .endOfFile
                .coerceAtLeast(0L)
        } catch (error: Exception) {
            runCatching { file.close() }
            throw error
        }
        return SmbRandomAccessFile(
            reopen = { openFileHandle(target, credentials) },
            file = file,
            length = length
        )
    }

    private fun openFileHandle(
        target: SmbTarget,
        credentials: SmbCredentials
    ): com.hierynomus.smbj.share.File = withShare(target, credentials) { share ->
        share.openFile(
            toSmbPath(target.path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            // Sequential access lets the server read ahead; random access explicitly disables it.
            EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY)
        )
    }

    /**
     * Connects to every configured SMB source once and keeps the connections pooled, so the
     * first playback does not pay the TCP negotiate + authentication cost.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val sources = runCatching { sourceDao.getEnabled() }.getOrDefault(emptyList())
        sources.filter { it.type == LocalSourceType.SMB }.forEach { source ->
            val target = SmbUri.parse(source.treeUri) ?: return@forEach
            val credentials = credentialsFor(source)
            if (!credentials.hasCredentials) return@forEach
            runCatching { withShare(target, credentials) { } }
                .onSuccess { rememberCredentials(target, credentials) }
        }
    }

    fun invalidateAll() {
        val stale = synchronized(lock) {
            val values = connections.values.toList()
            connections.clear()
            values
        }
        synchronized(credentialLock) { credentialCache.clear() }
        stale.forEach(::closeConnection)
    }

    private fun credentialsFor(source: LocalSourceEntity): SmbCredentials {
        val stored = source.credentialRef?.takeIf { it.isNotBlank() }
        val password = stored?.let(credentialStore::load)
        if (stored != null && password == null) {
            Log.w(TAG, "Stored SMB password for source ${source.id} could not be decrypted")
        }
        return SmbCredentials(
            username = source.username?.trim()?.takeIf { it.isNotEmpty() },
            password = password.orEmpty(),
            domain = source.domain?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun credentialsForPlayback(target: SmbTarget): SmbCredentials {
        cachedCredentialsFor(target)?.let { return it }
        val enabled = runBlocking(Dispatchers.IO) { sourceDao.getEnabled() }
        matchPlaybackSource(target, enabled)?.let { return it }
        // A source can still own the file while flagged disabled (stale state after an edit).
        // Do not silently downgrade to guest auth because of a flag lookup.
        val all = runBlocking(Dispatchers.IO) { sourceDao.getAll() }
        matchPlaybackSource(target, all)?.let { return it }
        Log.w(
            TAG,
            "No SMB source matches ${target.connectionKey}/${target.path}, falling back to guest auth"
        )
        return SmbCredentials(null, "", null)
    }

    /**
     * Picks the configured source whose folder contains the played file. Matching is done on the
     * parsed URI rather than the source type so a stale row type cannot break playback.
     */
    private fun matchPlaybackSource(
        target: SmbTarget,
        sources: List<LocalSourceEntity>
    ): SmbCredentials? {
        val candidates = sources.asSequence()
            .mapNotNull { source ->
                val sourceTarget = SmbUri.parse(source.treeUri) ?: return@mapNotNull null
                if (sourceTarget.connectionKey != target.connectionKey) return@mapNotNull null
                // The played file must live at or below the configured source folder.
                if (!SmbUri.isAtOrBelow(target.path, sourceTarget.path)) return@mapNotNull null
                CachedCredentials(rootPath = sourceTarget.path, credentials = credentialsFor(source))
            }
            .sortedByDescending { it.rootPath.length }
            .toList()
        val match = candidates.firstOrNull { it.credentials.hasCredentials }
            ?: candidates.firstOrNull()
        if (match == null) return null
        if (match.credentials.hasCredentials) {
            rememberCredentials(target.connectionKey, match)
        } else {
            Log.w(
                TAG,
                "SMB source for ${target.connectionKey}/${target.path} has no stored password, " +
                    "falling back to guest auth"
            )
        }
        return match.credentials
    }

    private fun cachedCredentialsFor(target: SmbTarget): SmbCredentials? =
        synchronized(credentialLock) {
            credentialCache[target.connectionKey]
                ?.filter { SmbUri.isAtOrBelow(target.path, it.rootPath) }
                ?.maxByOrNull { it.rootPath.length }
                ?.credentials
        }

    private fun rememberCredentials(target: SmbTarget, credentials: SmbCredentials) {
        if (!credentials.hasCredentials) return
        rememberCredentials(target.connectionKey, CachedCredentials(target.path, credentials))
    }

    private fun rememberCredentials(connectionKey: String, entry: CachedCredentials) {
        synchronized(credentialLock) {
            val entries = credentialCache.getOrPut(connectionKey) { mutableListOf() }
            entries.removeAll { it.rootPath == entry.rootPath }
            entries.add(entry)
        }
    }

    private fun <T> withShare(
        target: SmbTarget,
        credentials: SmbCredentials,
        block: (DiskShare) -> T
    ): T {
        val key = ConnectionKey(
            host = target.host.lowercase(Locale.US),
            port = target.port,
            share = target.share.lowercase(Locale.US),
            username = credentials.username,
            domain = credentials.domain,
            passwordFingerprint = credentials.password.takeIf { it.isNotEmpty() }?.hashCode()
        )
        val first = obtainConnection(key, target, credentials)
        return try {
            block(first.share)
        } catch (firstError: Exception) {
            // Only a lost session/connection is worth re-authenticating for. Retrying a
            // real API error (file missing, access denied) just doubles the latency.
            if (!isConnectionLoss(firstError)) throw firstError
            invalidate(key)
            val retry = obtainConnection(key, target, credentials)
            try {
                block(retry.share)
            } catch (retryError: Exception) {
                retryError.addSuppressed(firstError)
                throw retryError
            }
        }
    }

    private fun isConnectionLoss(error: Throwable): Boolean {
        if (error !is SMBApiException) return true
        return error.status in SESSION_LOST_STATUSES
    }

    private fun obtainConnection(
        key: ConnectionKey,
        target: SmbTarget,
        credentials: SmbCredentials
    ): ActiveConnection {
        synchronized(lock) {
            connections[key]?.let { return it }
            val created = createConnection(target, credentials)
            connections[key] = created
            return created
        }
    }

    private fun createConnection(target: SmbTarget, credentials: SmbCredentials): ActiveConnection {
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(30, TimeUnit.SECONDS)
            // SO_TIMEOUT must stay 0: the player keeps the file open while its buffer is full, and
            // a socket read timeout kills the idle session, making the next read fail mid-playback.
            .withSoTimeout(0)
            // One big read request per SMB round trip instead of the 1 MiB default.
            .withReadBufferSize(2 * 1024 * 1024)
            .withMultiProtocolNegotiate(true)
            .build()
        val client = SMBClient(config)
        var session: Session? = null
        return try {
            val connection: Connection = client.connect(target.host, target.port)
            val password = credentials.password
            val passwordChars = if (password.isEmpty()) CharArray(0) else password.toCharArray()
            session = try {
                val authentication = if (
                    credentials.username.isNullOrBlank() && password.isEmpty()
                ) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(
                        credentials.username.orEmpty(),
                        passwordChars,
                        credentials.domain
                    )
                }
                connection.authenticate(authentication)
            } finally {
                passwordChars.fill('\u0000')
            }
            val share = session.connectShare(target.share) as? DiskShare
                ?: throw IOException("SMB share is not a disk share")
            runCatching {
                val protocol = connection.negotiatedProtocol
                Log.i(
                    TAG,
                    "SMB ${target.host}:${target.port}/${target.share} dialect=${protocol.dialect} " +
                        "maxRead=${protocol.maxReadSize} maxWrite=${protocol.maxWriteSize}"
                )
            }
            ActiveConnection(client = client, share = share)
        } catch (error: Exception) {
            runCatching { session?.close() }
            runCatching { client.close() }
            if (error is IOException) throw error
            throw IOException(
                "SMB connection failed (${target.host}:${target.port}/${target.share}): " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        }
    }

    private fun invalidate(key: ConnectionKey) {
        val stale = synchronized(lock) { connections.remove(key) }
        stale?.let(::closeConnection)
    }

    private fun closeConnection(connection: ActiveConnection) {
        runCatching { connection.share.close() }
        runCatching { connection.client.close() }
    }

    private fun toSmbPath(path: String): String =
        SmbUri.normalizePath(path).replace('/', '\\')

    private data class CachedCredentials(
        val rootPath: String,
        val credentials: SmbCredentials
    )

    private data class ConnectionKey(
        val host: String,
        val port: Int,
        val share: String,
        val username: String?,
        val domain: String?,
        val passwordFingerprint: Int?
    )

    private data class ActiveConnection(
        val client: SMBClient,
        val share: DiskShare
    )

    private companion object {
        const val TAG = "SmbConnectionManager"
        val SESSION_LOST_STATUSES = setOf(
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED,
            NtStatus.STATUS_CONNECTION_RESET
        )
    }
}
