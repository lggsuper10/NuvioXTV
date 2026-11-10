package com.nuvio.tv.data.locallibrary

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalLibraryDatabaseModule {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_artwork_cache (
                    fileUri TEXT NOT NULL,
                    posterUri TEXT,
                    backdropUri TEXT,
                    sourceModifiedAt INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    resolvedAt INTEGER NOT NULL,
                    PRIMARY KEY(fileUri)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE local_artwork_cache ADD COLUMN source TEXT")
            database.execSQL("ALTER TABLE local_artwork_cache ADD COLUMN tmdbId INTEGER")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Matching got stricter (cleaner filename titles, online artwork first).
            // Old negative entries would otherwise hide resolvable artwork for days.
            database.execSQL(
                "DELETE FROM local_artwork_cache WHERE status != 'RESOLVED' OR source IS NULL"
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE local_source ADD COLUMN available INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE local_source ADD COLUMN type TEXT NOT NULL DEFAULT 'DEVICE'"
            )
            database.execSQL("ALTER TABLE local_source ADD COLUMN username TEXT")
            database.execSQL("ALTER TABLE local_source ADD COLUMN domain TEXT")
            database.execSQL("ALTER TABLE local_source ADD COLUMN credentialRef TEXT")
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS local_search_index (" +
                    "fileUri TEXT NOT NULL, " +
                    "searchText TEXT NOT NULL, " +
                    "indexedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(fileUri))"
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS smb_server (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "host TEXT NOT NULL, " +
                    "port INTEGER NOT NULL, " +
                    "username TEXT, " +
                    "domain TEXT, " +
                    "credentialRef TEXT, " +
                    "addedAt INTEGER NOT NULL)"
            )
            database.execSQL("ALTER TABLE local_source ADD COLUMN serverId INTEGER")
        }
    }

    @Provides
    @Singleton
    fun provideLocalLibraryDatabase(
        @ApplicationContext context: Context
    ): LocalLibraryDatabase = Room.databaseBuilder(
        context,
        LocalLibraryDatabase::class.java,
        "nuvio_local_library.db"
    )
        .addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8
        )
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun provideLocalSourceDao(database: LocalLibraryDatabase): LocalSourceDao =
        database.sourceDao()

    @Provides
    fun provideSmbServerDao(database: LocalLibraryDatabase): SmbServerDao =
        database.smbServerDao()

    @Provides
    fun provideLocalMediaItemDao(database: LocalLibraryDatabase): LocalMediaItemDao =
        database.itemDao()

    @Provides
    fun provideLocalArtworkDao(database: LocalLibraryDatabase): LocalArtworkDao =
        database.artworkDao()

    @Provides
    fun provideLocalSearchIndexDao(database: LocalLibraryDatabase): LocalSearchIndexDao =
        database.searchIndexDao()
}

