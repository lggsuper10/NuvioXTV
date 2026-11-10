package com.nuvio.tv.data.locallibrary

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class LocalMediaKind {
    MOVIE,
    SERIES_EPISODE
}

enum class LocalSourceType {
    DEVICE,
    SMB
}

@Entity(tableName = "local_source")
data class LocalSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val treeUri: String,
    val type: LocalSourceType = LocalSourceType.DEVICE,
    val username: String? = null,
    val domain: String? = null,
    val credentialRef: String? = null,
    /** The [SmbServerEntity] this folder belongs to. Null for device folders. */
    val serverId: Long? = null,
    val enabled: Boolean = true,
    val available: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val lastScanAt: Long? = null,
    val itemCount: Int = 0,
    val lastError: String? = null
)

/**
 * A saved SMB server (host + account). Folders are stored as [LocalSourceEntity] rows that point
 * back at a server, so the account only has to be entered once.
 */
@Entity(tableName = "smb_server")
data class SmbServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 445,
    val username: String? = null,
    val domain: String? = null,
    val credentialRef: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "local_media_item",
    indices = [
        Index("sourceId"),
        Index(value = ["sourceId", "groupKey"])
    ]
)
data class LocalMediaItemEntity(
    @PrimaryKey val id: String,
    val sourceId: Long,
    val fileUri: String,
    val fileName: String,
    val kind: LocalMediaKind,
    val title: String,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val groupKey: String? = null,
    val fileSizeBytes: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_artwork_cache")
data class LocalArtworkCacheEntity(
    @PrimaryKey val fileUri: String,
    val posterUri: String? = null,
    val backdropUri: String? = null,
    val sourceModifiedAt: Long = 0L,
    val status: String,
    val source: String? = null,
    val tmdbId: Int? = null,
    val resolvedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_search_index")
data class LocalSearchIndexEntity(
    @PrimaryKey val fileUri: String,
    val searchText: String,
    val indexedAt: Long = System.currentTimeMillis()
)

class LocalMediaKindConverter {
    @TypeConverter
    fun fromString(value: String): LocalMediaKind = LocalMediaKind.valueOf(value)

    @TypeConverter
    fun toString(value: LocalMediaKind): String = value.name
}

class LocalSourceTypeConverter {
    @TypeConverter
    fun fromString(value: String): LocalSourceType = LocalSourceType.valueOf(value)

    @TypeConverter
    fun toString(value: LocalSourceType): String = value.name
}

