package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Exercises the retention and ordering SQL in [ActionLogDao] against a real SQLite
 * engine.
 *
 * These are instrumented rather than JVM unit tests because SQLite is a native
 * device library — unlike `org.json`, there is no drop-in JVM replacement to test
 * against. Most tests use an in-memory database so they are fast and isolated;
 * [survivesCloseAndReopen] deliberately uses a file so it can prove the data
 * actually round-trips through disk.
 */
@RunWith(AndroidJUnit4::class)
class ActionLogDaoTest {

    private lateinit var context: Context
    private lateinit var db: MobileClawDb
    private lateinit var dao: ActionLogDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MobileClawDb::class.java).build()
        dao = db.actionLog()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(timeMs: Long, appName: String = "TestApp") = ActionLogEntity(
        timeMs = timeMs,
        appName = appName,
        key = "0|$appName|$timeMs",
        actionTitle = "Mark as read",
        suppliedText = null,
        result = "Pressed \"Mark as read\".",
        success = true
    )

    @Test
    fun observeRecent_returnsNewestFirst_andRespectsTheLimit() = runTest {
        listOf(100L, 300L, 200L).forEach { dao.insert(entity(it)) }

        val all = dao.observeRecent(10).first()
        assertEquals(listOf(300L, 200L, 100L), all.map { it.timeMs })

        val limited = dao.observeRecent(2).first()
        assertEquals(listOf(300L, 200L), limited.map { it.timeMs })
    }

    @Test
    fun trimToNewest_keepsOnlyTheNewestRows() = runTest {
        repeat(120) { dao.insert(entity(timeMs = it.toLong())) }
        assertEquals(120, dao.count())

        dao.trimToNewest(100)

        assertEquals(100, dao.count())
        // The 20 oldest went; the newest is untouched.
        val remaining = dao.observeRecent(200).first().map { it.timeMs }
        assertEquals(119L, remaining.first())
        assertEquals(20L, remaining.last())
    }

    /**
     * Retention orders by `id`, not `timeMs`, precisely so a device clock change
     * can't strand rows the sweep can never reach. A row stamped far in the future
     * must still be trimmable by insertion order.
     */
    @Test
    fun trimToNewest_isImmuneToOutOfOrderTimestamps() = runTest {
        dao.insert(entity(timeMs = Long.MAX_VALUE / 2, appName = "ClockSkewed"))
        repeat(10) { dao.insert(entity(timeMs = it.toLong())) }

        dao.trimToNewest(5)

        assertEquals(5, dao.count())
        val names = dao.observeRecent(10).first().map { it.appName }
        assertTrue("the clock-skewed row should have been trimmed", names.none { it == "ClockSkewed" })
    }

    @Test
    fun deleteOlderThan_dropsOnlyAgedRows() = runTest {
        val now = System.currentTimeMillis()
        val old = now - TimeUnit.DAYS.toMillis(120)
        dao.insert(entity(timeMs = old, appName = "Ancient"))
        dao.insert(entity(timeMs = now, appName = "Fresh"))

        val dropped = dao.deleteOlderThan(now - TimeUnit.DAYS.toMillis(90))

        assertEquals(1, dropped)
        assertEquals(listOf("Fresh"), dao.observeRecent(10).first().map { it.appName })
    }

    @Test
    fun deleteAll_emptiesTheTable() = runTest {
        repeat(5) { dao.insert(entity(it.toLong())) }
        dao.deleteAll()
        assertEquals(0, dao.count())
    }

    /**
     * The whole point of the change: an audit row written before the process dies is
     * still there when a fresh database instance opens the same file. Uses a
     * throwaway file so the app's real database is untouched.
     */
    @Test
    fun survivesCloseAndReopen() = runTest {
        val name = "round-trip-test.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, MobileClawDb::class.java, name).build()
            first.actionLog().insert(entity(timeMs = 42L, appName = "Persisted"))
            first.close() // everything the process knew is gone; only the file remains

            val second = Room.databaseBuilder(context, MobileClawDb::class.java, name).build()
            val rows = second.actionLog().observeRecent(10).first()
            second.close()

            assertEquals(1, rows.size)
            assertEquals("Persisted", rows.single().appName)
            assertEquals(42L, rows.single().timeMs)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
