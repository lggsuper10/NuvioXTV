package com.nuvio.tv.data.locallibrary

import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nuvio.tv.R
import com.nuvio.tv.core.locallibrary.LocalMediaScanner
import com.nuvio.tv.core.locallibrary.LocalScanResult
import com.nuvio.tv.core.smb.SmbBrowser
import com.nuvio.tv.core.smb.SmbConnectionManager
import com.nuvio.tv.core.smb.SmbCredentialStore
import com.nuvio.tv.core.smb.SmbCredentials
import com.nuvio.tv.core.smb.SmbFolder
import com.nuvio.tv.core.smb.SmbServerConfig
import com.nuvio.tv.core.smb.SmbUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalMediaDeleteResult(
    val requestedCount: Int,
    val deletedCount: Int,
    val failedCount: Int
)

@Singleton
class LocalLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceDao: LocalSourceDao,
    private val smbServerDao: SmbServerDao,
    private val itemDao: LocalMediaItemDao,
    private val searchIndexDao: LocalSearchIndexDao,
    private val artworkDao: LocalArtworkDao,
    private val scanner: LocalMediaScanner,
    private val smbConnectionManager: SmbConnectionManager,
    private val smbBrowser: SmbBrowser,
    private val smbCredentialStore: SmbCredentialStore
) {
    @Volatile
    private var lastAvailabilityCheckAt = 0L

    fun observeSources(): Flow<List<LocalSourceEntity>> = sourceDao.observeAll()

    fun observeEnabledSources(): Flow<List<LocalSourceEntity>> = sourceDao.observeAvailable()

    fun observeSourceKindCounts(): Flow<List<LocalSourceKindCount>> =
        itemDao.observeSourceKindCounts()

    fun observeAllItems(): Flow<List<LocalMediaItemEntity>> = itemDao.observeAll()

    suspend fun getAllItems(): List<LocalMediaItemEntity> = itemDao.getAll()

    suspend fun getAllSources(): List<LocalSourceEntity> = sourceDao.getAvailable()

    suspend fun getAllSourcesIncludingUnavailable(): List<LocalSourceEntity> = sourceDao.getEnabled()

    suspend fun hasAvailableSources(): Boolean = sourceDao.getAvailable().isNotEmpty()

    suspend fun getSource(id: Long): LocalSourceEntity? = sourceDao.getById(id)

    suspend fun addSource(name: String, treeUri: String): Long = withContext(Dispatchers.IO) {
        sourceDao.findByTreeUri(treeUri)?.let { return@withContext it.id }
        val uri = Uri.parse(treeUri)
        val isFileTree = uri.scheme == ContentResolver.SCHEME_FILE
        if (!isFileTree) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.recoverCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val fallbackName = if (isFileTree) {
            uri.path?.let(::File)?.name
        } else {
            runCatching {
                DocumentFile.fromTreeUri(context, uri)?.name
            }.getOrNull()
        }
        sourceDao.insert(
            LocalSourceEntity(
                name = name.trim().ifBlank { fallbackName ?: treeUri },
                treeUri = treeUri
            )
        )
    }

    // --- SMB servers -------------------------------------------------------

    fun observeServers(): Flow<List<SmbServerEntity>> = smbServerDao.observeAll()

    suspend fun getServers(): List<SmbServerEntity> = smbServerDao.getAll()

    suspend fun getServer(id: Long): SmbServerEntity? = smbServerDao.getById(id)

    /**
     * Verifies the account and returns the shares the server exposes. Share listing is best
     * effort: some servers refuse it, but the account can still open a share by name.
     */
    suspend fun testServer(config: SmbServerConfig): List<String> = withContext(Dispatchers.IO) {
        smbConnectionManager.testServerConnection(config)
        val credentials = SmbCredentials(
            username = config.username.trim().takeIf { it.isNotEmpty() },
            password = config.password,
            domain = config.domain.trim().takeIf { it.isNotEmpty() }
        )
        runCatching { smbBrowser.listShares(config.host, config.port, credentials) }
            .getOrDefault(emptyList())
    }

    suspend fun saveServer(config: SmbServerConfig, serverId: Long? = null): Long =
        withContext(Dispatchers.IO) {
            val host = config.host.trim()
            if (host.isBlank()) {
                throw IllegalArgumentException(
                    context.getString(R.string.local_media_smb_invalid_server_or_share)
                )
            }
            if (config.port !in 1..65535) {
                throw IllegalArgumentException(context.getString(R.string.local_media_smb_invalid_port))
            }
            val existing = serverId?.let { smbServerDao.getById(it) }
                ?: smbServerDao.findByAddress(host, config.port)
            val username = config.username.trim().takeIf { it.isNotEmpty() }
            val domain = config.domain.trim().takeIf { it.isNotEmpty() }
            val credentialRef = when {
                config.password.isNotEmpty() -> existing?.credentialRef ?: UUID.randomUUID().toString()
                else -> existing?.credentialRef
            }
            if (credentialRef != null && config.password.isNotEmpty()) {
                smbCredentialStore.save(credentialRef, config.password)
            }
            val name = config.displayName.trim().ifBlank { existing?.name ?: host }

            val savedId = if (existing == null) {
                smbServerDao.insert(
                    SmbServerEntity(
                        name = name,
                        host = host,
                        port = config.port,
                        username = username,
                        domain = domain,
                        credentialRef = credentialRef
                    )
                )
            } else {
                smbServerDao.update(
                    id = existing.id,
                    name = name,
                    host = host,
                    port = config.port,
                    username = username,
                    domain = domain,
                    credentialRef = credentialRef
                )
                // Folders store their own copy of the account (and the host in the URI), so they
                // have to follow the server row.
                sourceDao.getByServer(existing.id).forEach { source ->
                    val target = SmbUri.parse(source.treeUri)
                    val treeUri = target?.let { SmbUri.build(host, config.port, it.share, it.path) }
                        ?: source.treeUri
                    sourceDao.updateSmbSource(
                        id = source.id,
                        treeUri = treeUri,
                        name = source.name,
                        username = username,
                        domain = domain,
                        credentialRef = credentialRef
                    )
                }
                existing.id
            }
            smbConnectionManager.invalidateAll()
            smbBrowser.invalidate()
            savedId
        }

    suspend fun removeServer(serverId: Long) = withContext(Dispatchers.IO) {
        val server = smbServerDao.getById(serverId) ?: return@withContext
        sourceDao.getByServer(serverId).forEach { source ->
            sourceDao.delete(source)
            itemDao.deleteBySource(source.id)
        }
        server.credentialRef?.let(smbCredentialStore::delete)
        smbServerDao.delete(server)
        smbConnectionManager.invalidateAll()
        smbBrowser.invalidate()
    }

    suspend fun listServerShares(serverId: Long): List<String> = withContext(Dispatchers.IO) {
        val server = smbServerDao.getById(serverId)
            ?: throw IllegalArgumentException("Unknown SMB server $serverId")
        smbBrowser.listShares(server.host, server.port, smbConnectionManager.credentialsFor(server))
    }

    suspend fun listServerFolders(
        serverId: Long,
        share: String,
        path: String
    ): List<SmbFolder> = withContext(Dispatchers.IO) {
        val server = smbServerDao.getById(serverId)
            ?: throw IllegalArgumentException("Unknown SMB server $serverId")
        smbBrowser.listFolders(
            host = server.host,
            port = server.port,
            share = share,
            path = path,
            credentials = smbConnectionManager.credentialsFor(server)
        )
    }

    /** Registers one browsed folder as a media source and returns its id. */
    suspend fun addServerFolder(
        serverId: Long,
        share: String,
        path: String,
        displayName: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val server = smbServerDao.getById(serverId)
            ?: throw IllegalArgumentException("Unknown SMB server $serverId")
        val target = SmbUri.createTarget(server.host, server.port, share, path)
            ?: throw IllegalArgumentException(
                context.getString(R.string.local_media_smb_invalid_server_or_share)
            )
        val treeUri = target.sourceUri
        sourceDao.findByTreeUri(treeUri)?.let { return@withContext it.id }
        val name = displayName.trim().ifBlank {
            if (target.path.isBlank()) target.share else target.path.substringAfterLast('/')
        }
        sourceDao.insert(
            LocalSourceEntity(
                name = name,
                treeUri = treeUri,
                type = LocalSourceType.SMB,
                username = server.username,
                domain = server.domain,
                credentialRef = server.credentialRef,
                serverId = server.id
            )
        ).also { smbConnectionManager.invalidateAll() }
    }

    suspend fun removeSource(id: Long) = withContext(Dispatchers.IO) {
        val source = sourceDao.getById(id) ?: return@withContext
        if (source.type == LocalSourceType.SMB) {
            // Folders belonging to a saved server share its credential; only a standalone
            // source owns (and may delete) its own password.
            if (source.serverId == null) {
                source.credentialRef?.let(smbCredentialStore::delete)
            }
            smbConnectionManager.invalidateAll()
        } else {
            val uri = Uri.parse(source.treeUri)
            if (uri.scheme != ContentResolver.SCHEME_FILE) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }.recoverCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
        sourceDao.delete(source)
        itemDao.deleteBySource(source.id)
    }

    suspend fun scanSource(id: Long): LocalScanResult = withContext(Dispatchers.IO) {
        val source = sourceDao.getById(id)
            ?: return@withContext LocalScanResult(0)
        scanner.scan(source)
    }

    suspend fun scanAll(): LocalScanResult = withContext(Dispatchers.IO) {
        var total = 0
        var firstError: String? = null
        for (source in sourceDao.getEnabled()) {
            val result = scanner.scan(source)
            total += result.itemCount
            if (firstError == null) firstError = result.error
        }
        LocalScanResult(total, firstError)
    }

    suspend fun refreshAvailability(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAvailabilityCheckAt < AVAILABILITY_CHECK_INTERVAL_MS) {
            return@withContext false
        }
        lastAvailabilityCheckAt = now
        val unavailableMessage = context.getString(R.string.local_media_source_unavailable)
        var changed = false
        sourceDao.getEnabled().forEach { source ->
            val available = scanner.isAvailable(source)
            val desiredError = if (available) {
                null
            } else {
                source.lastError?.takeIf { it != unavailableMessage } ?: unavailableMessage
            }
            if (source.available != available || source.lastError != desiredError) {
                sourceDao.setAvailability(
                    id = source.id,
                    available = available,
                    lastError = desiredError
                )
                changed = true
            }
        }
        changed
    }

    suspend fun getItem(id: String): LocalMediaItemEntity? = itemDao.getById(id)

    suspend fun getMovies(sourceId: Long): List<LocalMediaItemEntity> =
        itemDao.getBySourceAndKind(sourceId, LocalMediaKind.MOVIE)

    suspend fun getItemsBySource(sourceId: Long): List<LocalMediaItemEntity> =
        itemDao.getBySource(sourceId)

    suspend fun filterDeletableItems(
        items: List<LocalMediaItemEntity>
    ): List<LocalMediaItemEntity> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val deviceSourceIds = sourceDao.getAll()
            .asSequence()
            .filter { it.type == LocalSourceType.DEVICE && it.available }
            .mapTo(HashSet()) { it.id }
        items.filter { it.sourceId in deviceSourceIds }
    }

    suspend fun deleteItems(
        items: List<LocalMediaItemEntity>
    ): LocalMediaDeleteResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext LocalMediaDeleteResult(0, 0, 0)

        val sourcesById = sourceDao.getAll().associateBy { it.id }
        val deletedItems = ArrayList<LocalMediaItemEntity>(items.size)
        var failedCount = 0
        for (item in items) {
            val source = sourcesById[item.sourceId]
            if (source?.type != LocalSourceType.DEVICE) {
                failedCount++
                continue
            }
            val deleted = runCatching { deleteMediaFile(item.fileUri) }.getOrDefault(false)
            if (deleted) {
                deletedItems += item
            } else {
                failedCount++
            }
        }

        if (deletedItems.isNotEmpty()) {
            val deletedIds = deletedItems.map { it.id }
            val deletedFileUris = deletedItems.map { it.fileUri }.distinct()
            itemDao.deleteByIds(deletedIds)
            searchIndexDao.deleteByFileUris(deletedFileUris)
            artworkDao.deleteByFileUris(deletedFileUris)
            deletedItems.map { it.sourceId }.distinct().forEach { sourceId ->
                sourceDao.refreshItemCount(sourceId)
            }
        }

        LocalMediaDeleteResult(
            requestedCount = items.size,
            deletedCount = deletedItems.size,
            failedCount = failedCount
        )
    }

    private fun deleteMediaFile(fileUri: String): Boolean {
        val uri = runCatching { Uri.parse(fileUri) }.getOrNull() ?: return false
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> {
                val path = uri.path ?: return false
                val file = File(path)
                file.isFile && file.delete()
            }

            ContentResolver.SCHEME_CONTENT -> {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                val document = DocumentFile.fromSingleUri(context, uri)
                document?.isFile == true && document.delete()
            }

            else -> false
        }
    }

    suspend fun getMovies(): List<LocalMediaItemEntity> =
        itemDao.getByKind(LocalMediaKind.MOVIE)

    suspend fun getSeriesEpisodes(): List<LocalMediaItemEntity> =
        itemDao.getByKind(LocalMediaKind.SERIES_EPISODE)

    suspend fun getSeriesEpisodes(sourceId: Long): List<LocalMediaItemEntity> =
        itemDao.getBySourceAndKind(sourceId, LocalMediaKind.SERIES_EPISODE)

    suspend fun getSeriesEpisodes(sourceId: Long, groupKey: String): List<LocalMediaItemEntity> =
        itemDao.getBySourceAndGroupKey(sourceId, groupKey)

    suspend fun getSeriesEpisodes(groupKey: String): List<LocalMediaItemEntity> =
        itemDao.getByGroupKey(groupKey)

    suspend fun searchItems(query: String, limit: Int = 60): List<LocalMediaItemEntity> =
        itemDao.search(query, limit)

    suspend fun hasSearchIndex(fileUri: String): Boolean =
        searchIndexDao.hasIndex(fileUri)

    suspend fun hasAnySearchIndex(fileUris: List<String>): Boolean =
        fileUris.isNotEmpty() && searchIndexDao.hasAny(fileUris)

    suspend fun getIndexedSearchFileUris(): Set<String> =
        searchIndexDao.getAllFileUris().toHashSet()

    suspend fun upsertSearchIndex(fileUris: List<String>, searchText: String) {
        val cleanText = searchText.trim()
        if (cleanText.isEmpty()) return
        val entries = fileUris
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .map { fileUri -> LocalSearchIndexEntity(fileUri = fileUri, searchText = cleanText) }
            .toList()
        if (entries.isNotEmpty()) searchIndexDao.upsertAll(entries)
    }

    private companion object {
        const val AVAILABILITY_CHECK_INTERVAL_MS = 5_000L
    }
}

