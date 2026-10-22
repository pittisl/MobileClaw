package edu.pitt.isl.mobileclaw.notiftest.skills.filesystem

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests `file_system` reads and redaction on the JVM by injecting the capability
 * answers normally obtained from Android.
 *
 * A card number read from a file must not reach the model. This test checks the
 * redaction boundary regardless of whether the app-side route or privileged backend
 * produced the text. Removing `Redaction.redact` from `FileSystemTool.exec` must fail
 * this assertion.
 */
class FileSystemToolReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tool(privileged: Boolean, direct: Boolean) =
        FileSystemTool(hasPrivileged = { privileged }, canReadDirectly = { direct })

    private fun run(t: FileSystemTool, op: String, path: String, extra: String = "") = runBlocking {
        t.invoke(JSONObject("""{"operation":"$op","path":"$path"$extra}"""))
    }

    @Test
    fun `a secret in a file the model reads is redacted before it is returned`() {
        val f = File(tmp.root, "wallet.txt").apply { writeText("visa 4111 1111 1111 1111 exp 12/27\nnote: call 412-555-0100") }
        val out = run(tool(privileged = false, direct = true), "cat", f.path)
        assertFalse(out, out.contains("4111 1111 1111 1111"))
        assertFalse(out, out.contains("412-555-0100"))
        assertTrue(out, out.contains("visa") && out.contains("note: call"))
    }

    @Test
    fun `with all-files access and no bridge, reads are served directly`() {
        File(tmp.root, "a.txt").writeText("alpha")
        val t = tool(privileged = false, direct = true)
        assertTrue(run(t, "ls", tmp.root.path).contains("a.txt"))
        assertTrue(run(t, "stat", tmp.root.path).contains("directory"))
        assertTrue(run(t, "find", tmp.root.path, ""","pattern":"*.txt"""").endsWith("a.txt"))
    }

    @Test
    fun `with neither route a read asks for the easier grant, all-files access`() {
        val out = run(tool(privileged = false, direct = false), "cat", File(tmp.root, "x").path)
        assertTrue(out, out.startsWith("[capability_unavailable: \"All files access\""))
    }

    @Test
    fun `the direct route never modifies anything`() {
        // A write without a bridge stays with ShellGate.modify, which has no backend to
        // stage against and says so; the grant did not turn into a write path.
        val out = run(tool(privileged = false, direct = true), "mkdir", File(tmp.root, "newdir").path)
        assertFalse(File(tmp.root, "newdir").exists())
        assertFalse(out, out.contains("Ready to create"))
    }

    @Test
    fun `policy still decides before either route is consulted`() {
        val out = run(tool(privileged = false, direct = true), "cat", "/data/data/edu.pitt.isl.mobileclaw.notiftest/databases/mobileclaw.db")
        assertTrue(out, out.startsWith("[fs_denied:"))
    }
}
