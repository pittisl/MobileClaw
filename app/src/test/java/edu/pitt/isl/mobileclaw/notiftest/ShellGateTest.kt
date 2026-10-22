package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the privileged-shell gate with a fake backend: backend availability,
 * argument-vector validation and confirmation before modifying commands execute.
 *
 * These are JVM tests because the policy is independent of Android. The Full
 * Automation setting is backed by real SharedPreferences, so the check that it cannot
 * bypass this gate lives in `ShellGateInstrumentedTest`.
 */
class ShellGateTest {

    /** Records what actually reached the backend. The assertions are mostly about this being empty. */
    private class FakeShell(
        override val level: ShellLevel = ShellLevel.SHELL,
        private val result: ShellResult = ShellResult(0, "ok", "")
    ) : PrivilegedShell {
        val commands = mutableListOf<List<String>>()
        val writes = mutableListOf<Pair<String, String>>()
        override suspend fun exec(argv: List<String>): ShellResult {
            commands += argv
            return result
        }
        override suspend fun write(path: String, content: String): ShellResult {
            writes += path to content
            return result
        }
        override suspend fun isAlive() = true
    }

    @After
    fun tearDown() {
        ShellRegistry.install(null)
    }

    // ---- no backend ---------------------------------------------------------

    @Test
    fun `with nothing connected a read reports the capability, not a failure`() = runTest {
        // The model is prompted to relay a capability_unavailable and try again — the
        // same contract every other permission-gated tool follows, so no tool needs
        // special handling for "the bridge isn't up".
        val out = ShellGate.read("file_system", "ls", listOf("ls", "--", "/sdcard"))

        assertTrue(out, out.startsWith("[capability_unavailable:"))
        assertTrue(out, out.contains("Privileged shell access"))
    }

    @Test
    fun `with nothing connected a modification is refused before the user is asked`() {
        // Staging first and discovering the backend is missing after the user says yes
        // would train them to approve dialogs that do nothing.
        val out = ShellGate.modify(
            "file_system", "rm", listOf("rm", "--", "/sdcard/x"),
            description = "Delete: /sdcard/x", reply = "waiting"
        )
        assertTrue(out, out.startsWith("[capability_unavailable:"))
        assertFalse("no confirmation should have been offered", out == "waiting")
    }

    // ---- reads --------------------------------------------------------------

    @Test
    fun `a read runs immediately and returns the output`() = runTest {
        val shell = FakeShell(result = ShellResult(0, "file-a\nfile-b", ""))
        ShellRegistry.install(shell)

        val out = ShellGate.read("file_system", "ls", listOf("ls", "--", "/sdcard"))

        assertEquals(listOf(listOf("ls", "--", "/sdcard")), shell.commands)
        assertEquals("file-a\nfile-b", out)
    }

    @Test
    fun `a failing command surfaces stderr rather than looking like success`() = runTest {
        // Tool output is prompt surface: swallowing stderr would leave the model to
        // guess, and a plausible guess about a failed command is worse than the error.
        ShellRegistry.install(FakeShell(result = ShellResult(2, "", "No such file or directory")))

        val out = ShellGate.read("file_system", "cat", listOf("cat", "--", "/sdcard/nope"))

        assertTrue(out, out.contains("exit 2") || out.contains("(exit 2)"))
        assertTrue(out, out.contains("No such file or directory"))
    }

    @Test
    fun `a malformed vector never reaches the backend`() = runTest {
        // A NUL truncates the argument inside execve, so what the daemon runs would
        // differ from what was vetted — the one thing argv framing cannot express.
        val shell = FakeShell()
        ShellRegistry.install(shell)

        val out = ShellGate.read("file_system", "cat", listOf("cat", "--", "/sdcard/a\u0000b"))

        assertTrue(out, out.startsWith("[shell_denied:"))
        assertTrue("nothing may have been executed", shell.commands.isEmpty())
    }

    @Test
    fun `an ordinary space or quote in a path is not malformed`() {
        // The counterpart to the NUL case, and the reason argv framing exists at all: a
        // space, a quote and a semicolon are just bytes inside one argument. If this
        // ever starts failing, something has begun building a command *string* again.
        assertEquals(null, FsPolicy.validateArgv(listOf("cat", "--", "/sdcard/My Files/a;b\".txt")))
    }

    // ---- the gate itself ----------------------------------------------------

    @Test
    fun `staging a modification does not execute it`() {
        // The core invariant. modify() returns a string for the model and has no
        // parameter that would let it run anything; only a confirmed proposal can.
        val shell = FakeShell()
        ShellRegistry.install(shell)

        val out = ShellGate.modify(
            "file_system", "rm", listOf("rm", "-rf", "--", "/sdcard/DCIM"),
            description = "Delete (recursive): /sdcard/DCIM",
            reply = "Ready to delete — waiting for your confirmation."
        )

        assertEquals("Ready to delete — waiting for your confirmation.", out)
        assertTrue("a proposal must not touch the device", shell.commands.isEmpty())
    }

    @Test
    fun `every modifying proposal opts out of Full Automation`() {
        // Full Automation exists so the agent can dismiss notifications unattended. It
        // must not also be a standing licence for an `rm -rf` reached by prompt
        // injection — so this flag is the gate's, not the call site's, and there is no
        // overload that sets it the other way.
        val exec = ShellGate.proposalFor(
            "file_system", "rm", listOf("rm", "-rf", "--", "/sdcard/DCIM"), "Delete"
        )
        val write = ShellGate.writeProposalFor("file_system", "/sdcard/a.txt", "hello", "Write")

        assertFalse("rm must be confirmed even in Full Automation", exec.allowFullAutomation)
        assertFalse("so must write", write.allowFullAutomation)
    }

    @Test
    fun `a confirmed proposal is what finally runs the command`() = runTest {
        val shell = FakeShell()
        ShellRegistry.install(shell)

        val proposal = ShellGate.proposalFor(
            "file_system", "rm", listOf("rm", "--", "/sdcard/x"), "Delete: /sdcard/x"
        )
        assertTrue("still nothing", shell.commands.isEmpty())

        proposal.execute()   // what the confirm dialog's "Do it" does

        assertEquals(listOf(listOf("rm", "--", "/sdcard/x")), shell.commands)
    }

    @Test
    fun `a backend that died between staging and confirming does not execute stale`() = runTest {
        // Every planned backend dies on reboot, and a proposal can sit on screen across
        // one. The backend is re-read at execute time rather than captured at staging.
        ShellRegistry.install(FakeShell())
        val proposal = ShellGate.proposalFor(
            "file_system", "rm", listOf("rm", "--", "/sdcard/x"), "Delete: /sdcard/x"
        )

        ShellRegistry.install(null)   // reboot, Shizuku not restarted, daemon gone

        val out = proposal.execute()
        assertTrue(out, out.startsWith("[capability_unavailable:"))
    }

    @Test
    fun `file content travels as a payload, never as an argument`() = runTest {
        // Content in argv is how `echo "$content" > file` gets reinvented. Keeping it a
        // separate parameter is what makes that impossible to express.
        val shell = FakeShell()
        ShellRegistry.install(shell)

        ShellGate.writeProposalFor(
            "file_system", "/sdcard/note.txt", "hello; rm -rf /sdcard", "Write"
        ).execute()

        assertTrue("no command was run at all", shell.commands.isEmpty())
        assertEquals(listOf("/sdcard/note.txt" to "hello; rm -rf /sdcard"), shell.writes)
    }

    // ---- the capability ------------------------------------------------------

    @Test
    fun `the capability tracks the registry, since a reboot revokes it silently`() {
        assertFalse(ShellRegistry.isConnected)
        assertEquals(ShellLevel.NONE, ShellRegistry.level)

        ShellRegistry.install(FakeShell(level = ShellLevel.SHELL))
        assertTrue(ShellRegistry.isConnected)
        assertEquals(ShellLevel.SHELL, ShellRegistry.level)

        ShellRegistry.install(null)
        assertFalse("nothing else has to happen for this to go away", ShellRegistry.isConnected)
    }
}
