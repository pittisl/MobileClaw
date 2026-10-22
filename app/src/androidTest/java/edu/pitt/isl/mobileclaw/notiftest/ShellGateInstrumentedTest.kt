package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one privileged-shell rule that cannot be checked on the JVM: **Full Automation
 * Mode does not reach the backend.**
 *
 * It needs a device because the flag lives in real SharedPreferences, which is exactly
 * why it is worth a device test — [ShellGateTest] can assert that
 * `allowFullAutomation` is false on a proposal, but only [PendingAction] reading a
 * genuinely-enabled setting proves that flag is honoured end to end. That is the
 * scenario the whole gate exists for: Full Automation is meant to let the agent
 * dismiss notifications unattended, not to hand a prompt injection an `rm -rf`.
 */
@RunWith(AndroidJUnit4::class)
class ShellGateInstrumentedTest {

    private class FakeShell : PrivilegedShell {
        override val level = ShellLevel.SHELL
        val commands = mutableListOf<List<String>>()
        val writes = mutableListOf<String>()
        override suspend fun exec(argv: List<String>): ShellResult {
            commands += argv
            return ShellResult(0, "done", "")
        }
        override suspend fun write(path: String, content: String): ShellResult {
            writes += path
            return ShellResult(0, "", "")
        }
        override suspend fun isAlive() = true
    }

    private lateinit var context: Context
    private lateinit var shell: FakeShell

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppSettings.init(context)
        shell = FakeShell()
        ShellRegistry.install(shell)
    }

    @After
    fun tearDown() {
        setFullAutomation(false)
        ShellRegistry.install(null)
    }

    private fun setFullAutomation(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(AppSettings.KEY_FULL_AUTOMATION, on).commit()
    }

    @Test
    fun fullAutomation_doesNotExecuteAPrivilegedDelete() {
        setFullAutomation(true)
        assertTrue("the dangerous mode really is on", AppSettings.fullAutomation)

        ShellGate.modify(
            "file_system", "rm", listOf("rm", "-rf", "--", "/sdcard/DCIM"),
            description = "Delete (recursive): /sdcard/DCIM",
            reply = "waiting"
        )

        // PendingAction's auto-execute path is a coroutine, so give it a chance to run
        // and then assert it didn't — a passing assertion checked too early would be
        // worthless for exactly this test.
        Thread.sleep(500)
        assertTrue(
            "Full Automation must not run a privileged delete: ${shell.commands}",
            shell.commands.isEmpty()
        )
    }

    @Test
    fun fullAutomation_doesNotExecuteAPrivilegedWrite() {
        setFullAutomation(true)

        ShellGate.modifyWrite(
            "file_system", "/sdcard/note.txt", "hello",
            description = "Write 5 chars to: /sdcard/note.txt",
            reply = "waiting"
        )

        Thread.sleep(500)
        assertTrue("nor a privileged write: ${shell.writes}", shell.writes.isEmpty())
    }

    @Test
    fun fullAutomation_stillAutoExecutesAnOrdinaryProposal() {
        // The control. Without it the tests above would also pass if Full Automation
        // were simply broken, and the carve-out would be proving nothing.
        setFullAutomation(true)
        var ran = false

        PendingAction.propose(PendingAction.Proposal(
            description = "Dismiss a notification",
            execute = { ran = true; "dismissed" }
        ))

        awaitUntil(message = "an ordinary proposal should auto-execute") { ran }
        assertTrue(ran)
    }

    @Test
    fun aConfirmedPrivilegedCommand_isRecordedWithItsExitStatus() = runTest {
        // The audit trail is what DataExport hands the user, and until a backend
        // existed it could only ever say "not connected".
        ActionLogStore.init(context)
        ActionLogStore.clear()
        awaitUntil(message = "store did not clear") { ActionLogStore.entries.value.isEmpty() }

        ShellGate.proposalFor(
            "file_system", "rm", listOf("rm", "--", "/sdcard/x"), "Delete: /sdcard/x"
        ).execute()

        awaitUntil(message = "nothing was recorded") { ActionLogStore.entries.value.isNotEmpty() }
        val entry = ActionLogStore.entries.value.last()
        assertEquals("file_system", entry.appName)
        assertEquals("rm", entry.key)
        assertTrue(entry.success)
        assertTrue("exit status belongs in the log: ${entry.result}", entry.result.contains("exit 0"))

        ActionLogStore.clear()
    }

    @Test
    fun aWriteIsLoggedByLengthNeverByContent() = runTest {
        // The audit trail leaves the sandbox via DataExport, so file bodies must not be
        // in it — a "write my passwords to a file" turn would otherwise put them in the
        // export twice.
        ActionLogStore.init(context)
        ActionLogStore.clear()
        awaitUntil(message = "store did not clear") { ActionLogStore.entries.value.isEmpty() }

        val secret = "hunter2-not-in-the-log"
        ShellGate.writeProposalFor(
            "file_system", "/sdcard/note.txt", secret, "Write"
        ).execute()

        awaitUntil(message = "nothing was recorded") { ActionLogStore.entries.value.isNotEmpty() }
        val entry = ActionLogStore.entries.value.last()
        assertFalse("file content leaked into the audit trail", entry.toString().contains(secret))
        assertTrue("but its size is recorded: ${entry.actionTitle}",
            entry.actionTitle.contains("${secret.length} bytes"))

        ActionLogStore.clear()
    }
}
