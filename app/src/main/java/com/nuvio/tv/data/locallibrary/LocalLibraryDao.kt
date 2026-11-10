package com.nuvio.tv.data.locallibrary

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSourceDao {
    @Query("SELECT * FROM local_source ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<LocalSourceEntity>>

    @Query("SELECT * FROM local_source WHERE enabled = 1 ORDER BY addedAt ASC")
    fun observeEnabled(): Flow<List<LocalSourceEntity>>

    @Query("SELECT * FROM local_source WHERE enabled = 1 AND available = 1 ORDER BY addedAt ASC")
    fun observeAvailable(): Flow<List<LocalSourceEntity>>

    @Query("SELECT * FROM local_source WHERE enabled = 1 ORDER BY addedAt ASC")
    suspend fun getEnabled(): List<LocalSourceEntity>

    @Query("SELECT * FROM local_source ORDER BY addedAt ASC")
    suspend fun getAll(): List<LocalSourceEntity>

    @Query("SELECT * FROM local_source WHERE enabled = 1 AND available = 1 ORDER BY addedAt ASC")
    suspend fun getAvailable(): List<LocalSourceEntity>

    @Query("SELECT * FROM local_source WHERE id = :id")
    suspend fun getById(id: Long): LocalSourceEntity?

    @Query("SELECT * FROM local_source WHERE treeUri = :treeUri LIMIT 1")
    suspend fun findByTreeUri(treeUri: String): LocalSourceEntity?

    @Insert
    suspend fun insert(source: LocalSourceEntity): Long

    @Delete
    suspend fun delete(source: LocalSourceEntity)

    @Query(
        "UPDATE local_source SET lastScanAt = :lastScanAt, itemCount = :itemCount, " +
            "lastError = :lastError, available = :available WHERE id = :id"
    )
    suspend fun updateScan(
        id: Long,
        lastScanAt: Long,
        itemCount: Int,
        lastError: String?,
        available: Boolean
    )

    @Query("UPDATE local_source SET available = :available, lastError = :lastError WHERE id = :id")
    suspend fun setAvailability(id: Long, available: Boolean, lastError: String?)

    @Query(
        "UPDATE local_source SET itemCount = " +
            "(SELECT COUNT(*) FROM local_media_item WHERE sourceId = :sourceId) WHERE id = :sourceId"
    )
    suspend fun refreshItemCount(sourceId: Long)

    @Query("UPDATE local_source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query(
        "UPDATE local_source SET treeUri = :treeUri, name = :name, username = :username, " +
            "domain = :domain, credentialRef = :credentialRef WHERE id = :id"
    )
    suspend fun updateSmbSource(
        id: Long,
        treeUri: String,
        name: String,
        username: String?,
        domain: String?,
        credentialRef: String?
    )

    @Query("SELECT * FROM local_source WHERE serverId = :serverId ORDER BY addedAt ASC")
    suspend fun getByServer(serverId: Long): List<LocalSourceEntity>

    @Query("SELECT * FROM local_source WHERE serverId = :serverId ORDER BY addedAt ASC")
    fun observeByServer(serverId: Long): Flow<List<LocalSourceEntity>>

    /** Keeps the (denormalized) credentials on every folder in sync with its server row. */
    @Query(
        "UPDATE local_source SET username = :username, domain = :domain, " +
            "credentialRef = :credentialRef WHERE serverId = :serverId"
    )
    suspend fun updateAccountForServer(
        serverId: Long,
        username: String?,
        domain: String?,
        credentialRef: String?
    )
}

@Dao
interface SmbServerDao {
    @Query("SELECT * FROM smb_server ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<SmbServerEntity>>

    @Query("SELECT * FROM smb_server ORDER BY addedAt ASC")
    suspend fun getAll(): List<SmbServerEntity>

    @Query("SELECT * FROM smb_server WHERE id = :id")
    suspend fun getById(id: Long): SmbServerEntity?

    @Query("SELECT * FROM smb_server WHERE host = :host AND port = :port LIMIT 1")
    suspend fun findByAddress(host: String, port: Int): SmbServerEntity?

    @Insert
    suspend fun insert(server: SmbServerEntity): Long

    @Delete
    suspend fun delete(server: SmbServerEntity)

    @Query(
        "UPDATE smb_server SET name = :name, host = :host, port = :port, username = :username, " +
            "domain = :domain, credentialRef = :credentialRef WHERE id = :id"
    )
    suspend fun update(
        id: Long,
        name: String,
        host: String,
        port: Int,
        username: String?,
        domain: String?,
        credentialRef: String?
    )
}

data class LocalSourceKindCount(
    val sourceId: Long,
    val kind: LocalMediaKind,
    val itemCount: Int
)

@Dao
interface LocalMediaItemDao {
    @Query("SELECT * FROM local_media_item")
    fun observeAll(): Flow<List<LocalMediaItemEntity>>

    @Query("SELECT * FROM local_media_item")
    suspend fun getAll(): List<LocalMediaItemEntity>

    @Query("SELECT * FROM local_media_item WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: Long): List<LocalMediaItemEntity>

    @Query(
        "SELECT sourceId, kind, COUNT(*) AS itemCount FROM local_media_item " +
            "GROUP BY sourceId, kind"
    )
    fun observeSourceKindCounts(): Flow<List<LocalSourceKindCount>>

    @Query("SELECT * FROM local_media_item WHERE id = :id")
    suspend fun getById(id: String): LocalMediaItemEntity?

    @Query(
        "SELECT * FROM local_media_item WHERE sourceId = :sourceId AND kind = :kind " +
            "ORDER BY title COLLATE NOCASE ASC, season ASC, episode ASC, fileName ASC"
    )
    suspend fun getBySourceAndKind(
        sourceId: Long,
        kind: LocalMediaKind
    ): List<LocalMediaItemEntity>

    @Query(
        "SELECT * FROM local_media_item WHERE kind = :kind " +
            "ORDER BY addedAt DESC, title COLLATE NOCASE ASC, fileName ASC"
    )
    suspend fun getByKind(kind: LocalMediaKind): List<LocalMediaItemEntity>

    @Query(
        "SELECT * FROM local_media_item WHERE sourceId = :sourceId AND groupKey = :groupKey " +
            "ORDER BY season ASC, episode ASC, fileName ASC"
    )
    suspend fun getBySourceAndGroupKey(
        sourceId: Long,
        groupKey: String
    ): List<LocalMediaItemEntity>

    @Query(
        "SELECT * FROM local_media_item WHERE groupKey = :groupKey " +
            "ORDER BY season ASC, episode ASC, fileName ASC"
    )
    suspend fun getByGroupKey(groupKey: String): List<LocalMediaItemEntity>

    @Query(
        "SELECT item.* FROM local_media_item item " +
            "LEFT JOIN local_search_index search ON search.fileUri = item.fileUri " +
            "WHERE (item.title LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "item.fileName LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "item.year LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "search.searchText LIKE '%' || :query || '%' COLLATE NOCASE) " +
            "ORDER BY item.addedAt DESC, item.title COLLATE NOCASE ASC, item.fileName ASC " +
            "LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<LocalMediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LocalMediaItemEntity>)

    @Query("DELETE FROM local_media_item WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    @Query("DELETE FROM local_media_item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    suspend fun replaceForSource(sourceId: Long, items: List<LocalMediaItemEntity>) {
        deleteBySource(sourceId)
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Dao
interface LocalSearchIndexDao {
    @Query("SELECT * FROM local_search_index WHERE fileUri = :fileUri")
    suspend fun getByFileUri(fileUri: String): LocalSearchIndexEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM local_search_index WHERE fileUri = :fileUri)")
    suspend fun hasIndex(fileUri: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM local_search_index WHERE fileUri IN (:fileUris))")
    suspend fun hasAny(fileUris: List<String>): Boolean

    @Query("SELECT fileUri FROM local_search_index")
    suspend fun getAllFileUris(): List<String>

    @Query("DELETE FROM local_search_index WHERE fileUri IN (:fileUris)")
    suspend fun deleteByFileUris(fileUris: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<LocalSearchIndexEntity>)
}

@Dao
interface LocalArtworkDao {
    @Query("SELECT * FROM local_artwork_cache WHERE fileUri = :fileUri")
    suspend fun getByFileUri(fileUri: String): LocalArtworkCacheEntity?

    @Query("SELECT * FROM local_artwork_cache WHERE fileUri IN (:fileUris)")
    suspend fun getByFileUris(fileUris: List<String>): List<LocalArtworkCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LocalArtworkCacheEntity)

    @Query("DELETE FROM local_artwork_cache WHERE fileUri = :fileUri")
    suspend fun deleteByFileUri(fileUri: String)

    @Query("DELETE FROM local_artwork_cache WHERE fileUri IN (:fileUris)")
    suspend fun deleteByFileUris(fileUris: List<String>)
}

