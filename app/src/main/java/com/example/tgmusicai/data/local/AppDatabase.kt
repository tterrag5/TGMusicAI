package com.example.tgmusicai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.tgmusicai.data.local.dao.AiSongTagsDao
import com.example.tgmusicai.data.local.dao.AlarmDao
import com.example.tgmusicai.data.local.dao.ListeningHistoryDao
import com.example.tgmusicai.data.local.dao.PendingDownloadDao
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.dao.SongStatsDao
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.AiSongTags
import com.example.tgmusicai.data.local.entity.ListeningHistory
import com.example.tgmusicai.data.local.entity.PendingDownload
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongStats

/**
 * The main Room database for the application.
 * Ties together all entities and DAOs.
 * Version = 6 added description to Playlist, supported soft themes & Liked Music system playlist.
 * Version = 7 added youtube_playlist_id/last_synced_at to Playlist for Google/YouTube playlist sync.
 * Version = 8 added pending_downloads to resume interrupted cloud downloads on next launch.
 * Version = 9 added ai_song_tags -- a standalone table owned by the `ai` containment layer, so a
 * bug in on-device AI tagging/embedding code can never touch the core schema.
 * Version = 10 added song_stats.totalListenTimeMs and the listening_history table, powering the
 * Stats screen's total-listening-time hero metric and weekly listening trend chart.
 * Version = 11 added alarms.forceMaxVolume/volumeRampUp -- these used to be one global setting
 * pair in AppPreferences applied to every alarm; now each alarm picks its own.
 */
@Database(
    entities = [
        Song::class,
        Playlist::class,
        PlaylistSongCrossRef::class,
        SongStats::class,
        Alarm::class,
        PendingDownload::class,
        AiSongTags::class,
        ListeningHistory::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /** DAO for the `songs` table: local library + cloud-streamed track metadata. */
    abstract fun songDao(): SongDao
    /** DAO for `playlists` and the `playlist_song_cross_ref` join table. */
    abstract fun playlistDao(): PlaylistDao
    /** DAO for `song_stats`: play counts, last-played timestamp, total listen time. */
    abstract fun songStatsDao(): SongStatsDao
    /** DAO for `alarms`: scheduled music-alarm configuration. */
    abstract fun alarmDao(): AlarmDao
    /** DAO for `pending_downloads`: in-flight/interrupted YouTube downloads to resume on launch. */
    abstract fun pendingDownloadDao(): PendingDownloadDao
    /** DAO for `ai_song_tags`: on-device AI tag/embedding results, kept isolated from core tables. */
    abstract fun aiSongTagsDao(): AiSongTagsDao
    /** DAO for `listening_history`: per-session listening-duration log used for stats trends. */
    abstract fun listeningHistoryDao(): ListeningHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Additive-only migration: adds nullable columns for YouTube playlist sync. Deliberately
         * NOT handled by [fallbackToDestructiveMigration], which would silently wipe every user's
         * local library, playlists, stats, and alarms on upgrade.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN youtube_playlist_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE playlists ADD COLUMN last_synced_at INTEGER DEFAULT NULL")
            }
        }

        /**
         * Additive-only migration: creates `pending_downloads` so an interrupted YouTube
         * download can be resumed on next app launch instead of being silently lost.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_downloads (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        uploader TEXT NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        queuedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Additive-only migration: creates `ai_song_tags` as a standalone table (own primary
         * key `songId`, no foreign key to `songs`) so on-device AI tagging/embedding bugs can
         * never corrupt or cascade-delete into the core schema.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_song_tags (
                        songId INTEGER NOT NULL PRIMARY KEY,
                        tags TEXT,
                        lyricsEmbedding TEXT,
                        computedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Additive-only migration: adds `song_stats.totalListenTimeMs` (defaulted to 0 for
         * existing rows) and creates `listening_history`, a per-session log of play duration
         * used to power the Stats screen's total-listening-time and weekly-trend views.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE song_stats ADD COLUMN totalListenTimeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS listening_history (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        songId INTEGER NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds the per-alarm forceMaxVolume/volumeRampUp columns, defaulting existing alarms to
         * both off. There's no way to seed these from the old global AppPreferences toggle they
         * replace -- a Room [Migration] only has access to the raw SQLite database, not DataStore
         * -- so an upgrading user's existing alarms start from the same default as brand new ones
         * and just need the toggle re-applied per alarm if they want it back.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN forceMaxVolume INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN volumeRampUp INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the acoustic profile column to `ai_song_tags`.
         *
         * Existing rows are left null rather than backfilled here: the value can only be produced
         * by re-running YAMNet over the audio, which is minutes of work for a large library and
         * has no business happening inside a migration.
         * [com.example.tgmusicai.ai.AiFeatureManager.analyzeSongIfNeeded] fills them in on the
         * next backfill instead.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_song_tags ADD COLUMN audioProfile TEXT")
            }
        }

        /**
         * Adds the user-ordering column to `playlists`.
         *
         * Defaulting to 0 leaves every existing playlist tied, which the ordering queries break by
         * falling back to `createdAt` -- so an upgrading user sees exactly the order they had
         * until the first time they drag something.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Returns the app-wide singleton [AppDatabase], creating it on first call.
         * Double-checked locking (`synchronized` + null check twice) avoids building the
         * database twice if two threads race to call this before [INSTANCE] is set.
         * [fallbackToDestructiveMigration] is a safety net for any DB version bump that
         * doesn't get an explicit `MIGRATION_x_y` below -- it wipes and recreates the DB
         * rather than crashing, but every migration so far is written explicitly to avoid
         * losing user data.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tgmusicai_database"
                )
                .addMigrations(
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13,
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
