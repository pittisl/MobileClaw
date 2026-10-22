package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's SQLite database, via Room.
 *
 * Lives at the Room default — `context.getDatabasePath(NAME)`, i.e.
 * `/data/data/<pkg>/databases/` — deliberately, and no path override is offered.
 * This file holds the most sensitive data the app has. App-private internal storage puts
 * it behind the uid + SELinux boundary. External storage (`/sdcard/Android/data/…`) would not: it has no
 * POSIX permissions, is reachable over adb/USB/file managers, and SQLite on the
 * emulated FUSE volume has known file-locking problems. It is also excluded from
 * cloud backup and device transfer in `data_extraction_rules.xml` /
 * `backup_rules.xml` — the user gets their data out via [DataExport] instead, which
 * is a deliberate choice they make rather than one the backup system makes for them.
 *
 * Holds the action audit trail, chat history and the conversations it is grouped into,
 * triage decisions, and the agent's memory. Adding an entity means bumping the
 * version and supplying a migration below; the generated schema JSON under
 * `app/schemas/` is the baseline to diff against.
 */
@Database(
    entities = [
        ActionLogEntity::class,
        ChatMessageEntity::class,
        ActionDecisionEntity::class,
        MemoryEntity::class,
        ChatSessionEntity::class,
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(ChatConverters::class)
abstract class MobileClawDb : RoomDatabase() {

    abstract fun actionLog(): ActionLogDao

    abstract fun chatMessages(): ChatMessageDao

    abstract fun actionDecisions(): ActionDecisionDao

    abstract fun memories(): MemoryDao

    abstract fun chatSessions(): ChatSessionDao

    companion object {
        private const val TAG = "MobileClaw-DB"

        const val NAME = "mobileclaw.db"

        @Volatile
        private var instance: MobileClawDb? = null

        /**
         * v1 → v2: adds `chat_message`. The SQL must match what Room generates for
         * [ChatMessageEntity] exactly — Room verifies the schema identity hash when
         * it opens the database and refuses to proceed on a mismatch, so a typo here
         * is a hard failure on the next launch rather than silent drift.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_message` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`actions` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_message_sessionId_timestampMs` " +
                        "ON `chat_message` (`sessionId`, `timestampMs`)"
                )
            }
        }

        /**
         * v2 → v3: adds `action_decision`. Same rule as [MIGRATION_1_2] — the DDL is
         * copied from `app/schemas/…/3.json`'s `createSql` rather than written by hand,
         * because Room checks the schema identity hash on open and a single character
         * of drift is a hard failure on the next launch.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `action_decision` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timeMs` INTEGER NOT NULL, " +
                        "`notificationKey` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`priority` TEXT NOT NULL, " +
                        "`reasoning` TEXT NOT NULL, " +
                        "`replyText` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`snoozeDurationMinutes` INTEGER, " +
                        "`actionTitle` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_action_decision_timeMs` " +
                        "ON `action_decision` (`timeMs`)"
                )
            }
        }

        /**
         * v3 → v4: adds `memory`. Same rule as the two above — the DDL is
         * copied from `app/schemas/…/4.json`'s `createSql`, not written by hand.
         *
         * Non-destructive migration applies with more force here than for the other tables: this is the one
         * store holding data the *user asked to keep*, so a destructive fallback would
         * delete the only thing in the app nobody else put there.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`provenance` TEXT NOT NULL, " +
                        "`redacted` INTEGER NOT NULL, " +
                        "`createdMs` INTEGER NOT NULL, " +
                        "`updatedMs` INTEGER NOT NULL, " +
                        "`dayKey` TEXT, " +
                        "`supersededBy` INTEGER, " +
                        "`pinned` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_createdMs` " +
                        "ON `memory` (`createdMs`)"
                )
            }
        }

        /**
         * v4 → v5: adds `chat_session`. No backfill of `chat_message` is needed
         * — every existing row already carries [ChatStore.DEFAULT_SESSION] — but the row
         * describing that session is created by [ChatStore.init] rather than here, so the
         * same code path runs on a fresh install and on an upgrade.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_session` (" +
                        "`sessionId` TEXT NOT NULL, " +
                        "`title` TEXT, " +
                        "`createdMs` INTEGER NOT NULL, " +
                        "`lastActiveMs` INTEGER NOT NULL, " +
                        "`agentSpecId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`sessionId`))"
                )
            }
        }

        fun get(context: Context): MobileClawDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MobileClawDb::class.java,
                    NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }

        fun databaseFile(context: Context): File = context.getDatabasePath(NAME)

        /**
         * Folds the write-ahead log back into the main `.db` file.
         *
         * Room runs in WAL mode, so recent writes live in `mobileclaw.db-wal` and the
         * `.db` file on its own is a stale snapshot. Copying it without checkpointing
         * first silently drops the newest — and most interesting — rows, which is
         * exactly the failure a user exporting their audit trail would never notice.
         * Always call this before reading the file directly.
         */
        suspend fun checkpoint(context: Context) = withContext(Dispatchers.IO) {
            try {
                get(context).openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() } // the PRAGMA only runs once the cursor is read
            } catch (e: Exception) {
                // Non-fatal: the export still produces a valid, slightly older database.
                Log.w(TAG, "WAL checkpoint failed — export may omit the newest rows", e)
            }
        }
    }
}
