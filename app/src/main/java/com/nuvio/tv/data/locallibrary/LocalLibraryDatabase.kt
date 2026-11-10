package com.nuvio.tv.data.locallibrary

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LocalSourceEntity::class,
        SmbServerEntity::class,
        LocalMediaItemEntity::class,
        LocalArtworkCacheEntity::class,
        LocalSearchIndexEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(LocalMediaKindConverter::class, LocalSourceTypeConverter::class)
abstract class LocalLibraryDatabase : RoomDatabase() {
    abstract fun sourceDao(): LocalSourceDao
    abstract fun smbServerDao(): SmbServerDao
    abstract fun itemDao(): LocalMediaItemDao
    abstract fun artworkDao(): LocalArtworkDao
    abstract fun searchIndexDao(): LocalSearchIndexDao
}

