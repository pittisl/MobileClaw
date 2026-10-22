package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [ActionLogStore] as callers actually use it — the production path,
 * against the real on-disk database that [MobileClawApp] opened at process start.
 * (The store is a singleton whose `init` is idempotent, so a test DAO can't be
 * injected; running against the real database is the honest option, and each test
 * clears before and after so nothing is read from or left behind for a real run.)
 */
@RunWith(AndroidJUnit4::class)
class ActionLogStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ActionLogStore.init(context) // idempotent; MobileClawApp already did this
        ActionLogStore.clear()
        awaitUntil(message = "store did not clear") { ActionLogStore.entries.value.isEmpty() }
    }

    @After
    fun tearDown() {
        ActionLogStore.clear()
    }

    private fun recordOne(
        appName: String = "TestApp",
        actionTitle: String = "Mark as read",
        suppliedText: String? = null
    ) {
        ActionLogStore.record(
            appName = appName,
            key = "0|$appName|1",
            actionTitle = actionTitle,
            suppliedText = suppliedText,
            result = "Pressed \"$actionTitle\".",
            success = true
        )
        awaitUntil(message = "record() never surfaced in entries") {
            ActionLogStore.entries.value.isNotEmpty()
        }
    }

    /**
     * The important one. Redaction has to happen *before the write*, not just before
     * data goes to the cloud: the row is what [DataExport] hands to the user, so an
     * unredacted secret here would outlive the notification and leave the device in
     * an export. Asserted against the database itself, not the in-memory flow.
     */
    @Test
    fun record_redactsSensitiveTextBeforeItReachesDisk() = runTest {
        recordOne(suppliedText = "Your verification code is 481529, do not share it")

        val stored = MobileClawDb.get(context).actionLog().observeRecent(1).first().single()
        val text = requireNotNull(stored.suppliedText)

        assertFalse("the OTP must not be stored: $text", text.contains("481529"))
        assertTrue("expected a redaction marker, got: $text", text.contains("[REDACTED]"))
    }

    /**
     * The export dialog promises exactly this: codes and credentials are redacted,
     * but app names, timestamps and button labels are not. Pin that contract down so
     * the dialog text can't quietly drift away from the behaviour.
     */
    @Test
    fun record_keepsNonSensitiveFieldsIntact() = runTest {
        recordOne(appName = "Gmail", actionTitle = "Archive")

        val stored = MobileClawDb.get(context).actionLog().observeRecent(1).first().single()

        assertEquals("Gmail", stored.appName)
        assertEquals("Archive", stored.actionTitle)
        assertTrue(stored.timeMs > 0)
        assertTrue(stored.success)
    }

    @Test
    fun record_withNoSuppliedText_storesNull() = runTest {
        recordOne(suppliedText = null)

        val stored = MobileClawDb.get(context).actionLog().observeRecent(1).first().single()
        assertEquals(null, stored.suppliedText)
    }

    /** Room re-runs the DAO query on every write, so the UI-facing flow tracks the table. */
    @Test
    fun entries_reflectWritesAndAreOldestFirst() {
        recordOne(appName = "First")
        ActionLogStore.record("Second", "k|2", "Reply", null, "Reply sent.", true)
        awaitUntil(message = "second record never arrived") {
            ActionLogStore.entries.value.size == 2
        }

        assertEquals(listOf("First", "Second"), ActionLogStore.entries.value.map { it.appName })
    }

    @Test
    fun clear_emptiesTheTableNotJustMemory() = runTest {
        recordOne()

        ActionLogStore.clear()
        awaitUntil(message = "clear() did not empty the store") {
            ActionLogStore.entries.value.isEmpty()
        }

        assertEquals(0, MobileClawDb.get(context).actionLog().count())
    }
}
