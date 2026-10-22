package edu.pitt.isl.mobileclaw.notiftest

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations are the one part of a database you cannot fix after shipping, so they
 * get tested rather than eyeballed.
 *
 * [MigrationTestHelper.runMigrationsAndValidate] compares the schema the migration
 * actually produces against the exported `2.json` — hand-written DDL that drifts
 * even slightly from what Room generates is caught here, instead of throwing on a
 * user's device the next time the app launches.
 */
@RunWith(AndroidJUnit4::class)
class MobileClawDbMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MobileClawDb::class.java
    )

    @Test
    fun migrate1To2_addsChatMessage_andKeepsTheAuditTrail() {
        helper.createDatabase(TEST_DB, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO action_log (timeMs, appName, key, actionTitle, suppliedText, result, success) " +
                    "VALUES (1700000000000, 'Gmail', '0|Gmail|1', 'Archive', NULL, 'Pressed \"Archive\".', 1)"
            )
        }

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, MobileClawDb.MIGRATION_1_2)

        // The existing audit row must survive — a migration that quietly drops the
        // record of what the agent did would be worse than no migration.
        v2.query("SELECT appName FROM action_log").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Gmail", c.getString(0))
        }

        // And the new table is usable, not merely present.
        v2.execSQL(
            "INSERT INTO chat_message (sessionId, role, content, timestampMs, actions) " +
                "VALUES ('default', 'USER', 'hello', 1700000000001, '[]')"
        )
        v2.query("SELECT content FROM chat_message").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("hello", c.getString(0))
        }
    }

    @Test
    fun migrate2To3_addsActionDecision_andKeepsWhatWasAlreadyThere() {
        helper.createDatabase(TEST_DB, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO action_log (timeMs, appName, key, actionTitle, suppliedText, result, success) " +
                    "VALUES (1700000000000, 'Gmail', '0|Gmail|1', 'Archive', NULL, 'Pressed \"Archive\".', 1)"
            )
            v2.execSQL(
                "INSERT INTO chat_message (sessionId, role, content, timestampMs, actions) " +
                    "VALUES ('default', 'USER', 'hello', 1700000000001, '[]')"
            )
        }

        val v3 = helper.runMigrationsAndValidate(TEST_DB, 3, true, MobileClawDb.MIGRATION_2_3)

        // Both existing tables must come through untouched. A migration that adds a
        // table but drops the audit trail or the conversation is the failure mode worth
        // testing for — it is silent, and only noticed long after the upgrade.
        v3.query("SELECT appName FROM action_log").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Gmail", c.getString(0))
        }
        v3.query("SELECT content FROM chat_message").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("hello", c.getString(0))
        }

        // And the new table is usable, not merely present. The nullable snooze column
        // is written explicitly: it is the only nullable column in the table, so a
        // wrong NOT NULL in the hand-written DDL would show up precisely here.
        v3.execSQL(
            "INSERT INTO action_decision " +
                "(timeMs, notificationKey, action, priority, reasoning, replyText, " +
                "confidence, snoozeDurationMinutes, actionTitle) " +
                "VALUES (1700000000002, '0|Todoist|3', 'SNOOZE', 'MEDIUM', 'Due later.', '', 0.87, NULL, '')"
        )
        v3.query("SELECT notificationKey, snoozeDurationMinutes FROM action_decision").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("0|Todoist|3", c.getString(0))
            assertEquals(true, c.isNull(1))
        }
    }

    @Test
    fun migrate3To4_addsMemory_andKeepsWhatWasAlreadyThere() {
        helper.createDatabase(TEST_DB, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO action_log (timeMs, appName, key, actionTitle, suppliedText, result, success) " +
                    "VALUES (1700000000000, 'Gmail', '0|Gmail|1', 'Archive', NULL, 'Pressed \"Archive\".', 1)"
            )
            v3.execSQL(
                "INSERT INTO chat_message (sessionId, role, content, timestampMs, actions) " +
                    "VALUES ('default', 'USER', 'hello', 1700000000001, '[]')"
            )
            v3.execSQL(
                "INSERT INTO action_decision " +
                    "(timeMs, notificationKey, action, priority, reasoning, replyText, " +
                    "confidence, snoozeDurationMinutes, actionTitle) " +
                    "VALUES (1700000000002, '0|Todoist|3', 'SNOOZE', 'MEDIUM', 'Due later.', '', 0.87, NULL, '')"
            )
        }

        val v4 = helper.runMigrationsAndValidate(TEST_DB, 4, true, MobileClawDb.MIGRATION_3_4)

        // All three existing tables come through. Same failure mode as v2→v3, and it stays
        // worth asserting: adding a table while dropping a record is silent.
        v4.query("SELECT appName FROM action_log").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Gmail", c.getString(0))
        }
        v4.query("SELECT content FROM chat_message").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("hello", c.getString(0))
        }
        v4.query("SELECT notificationKey FROM action_decision").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("0|Todoist|3", c.getString(0))
        }

        // The new table is usable, not merely present. Both nullable columns are written
        // NULL explicitly — `dayKey` and `supersededBy` are the only two in the table, and
        // a spurious NOT NULL in the hand-written DDL shows up precisely here. Row 2 fills
        // them in, so the columns are exercised in both states.
        v4.execSQL(
            "INSERT INTO memory (content, provenance, redacted, createdMs, updatedMs, " +
                "dayKey, supersededBy, pinned) " +
                "VALUES ('The user prefers aisle seats', 'USER_STATED', 0, " +
                "1700000000003, 1700000000003, NULL, NULL, 0)"
        )
        v4.execSQL(
            "INSERT INTO memory (content, provenance, redacted, createdMs, updatedMs, " +
                "dayKey, supersededBy, pinned) " +
                "VALUES ('Dismissed 4 notifications', 'DAILY_SUMMARY', 0, " +
                "1700000000004, 1700000000005, '2026-08-07', 1, 1)"
        )
        v4.query(
            "SELECT content, provenance, dayKey, supersededBy FROM memory ORDER BY id"
        ).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("The user prefers aisle seats", c.getString(0))
            assertEquals("USER_STATED", c.getString(1))
            assertEquals(true, c.isNull(2))
            assertEquals(true, c.isNull(3))
            c.moveToNext()
            assertEquals("2026-08-07", c.getString(2))
            assertEquals(1L, c.getLong(3))
        }
    }

    /**
     * v4 → v5 adds `chat_session`. The interesting part is what it does *not* do:
     * `chat_message.sessionId` has been written since v1, so there is no backfill, and the
     * row describing the default session is created by `ChatStore.init` rather than by the
     * migration — one code path for a fresh install and an upgrade, instead of two that can
     * disagree. This asserts the turns survive and that the new table takes a null title,
     * which is the state that lets the first user turn name a session automatically.
     */
    @Test
    fun migrate4To5_addsChatSession_andLeavesExistingTurnsAlone() {
        helper.createDatabase(TEST_DB, 4).use { v4 ->
            v4.execSQL(
                "INSERT INTO chat_message (sessionId, role, content, timestampMs, actions) " +
                    "VALUES ('default', 'USER', 'what did I miss?', 1700000000001, '[]')"
            )
        }

        val v5 = helper.runMigrationsAndValidate(TEST_DB, 5, true, MobileClawDb.MIGRATION_4_5)

        v5.query("SELECT sessionId, content FROM chat_message").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("default", c.getString(0))
            assertEquals("what did I miss?", c.getString(1))
        }

        v5.execSQL(
            "INSERT INTO chat_session (sessionId, title, createdMs, lastActiveMs, agentSpecId) " +
                "VALUES ('default', NULL, 1700000000000, 1700000000000, 'default')"
        )
        v5.query("SELECT title, agentSpecId FROM chat_session").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("an unnamed session is what lets the first turn name it", true, c.isNull(0))
            assertEquals("default", c.getString(1))
        }
    }
}
