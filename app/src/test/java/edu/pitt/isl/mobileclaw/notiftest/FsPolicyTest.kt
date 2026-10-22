package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hostile-input tests for [FsPolicy] — the vetting that stands between a
 * model-supplied path and a shell running as uid 2000.
 *
 * These run on the JVM with no device, no permissions and no shell bridge, which is
 * the reason the policy lives in its own object: the interesting cases are exactly
 * the ones nobody wants to reproduce on a real device.
 */
class FsPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun allowed(path: String, access: FsPolicy.Access) =
        FsPolicy.vet(path, access) as? FsPolicy.Verdict.Allow

    private fun denial(path: String, access: FsPolicy.Access) =
        (FsPolicy.vet(path, access) as? FsPolicy.Verdict.Deny)?.reason

    // ---- Defect 1: nothing is interpolated, so metacharacters are just bytes ----

    @Test
    fun `shell metacharacters in a path survive as one argument`() {
        // The historical break: `ls "$path"` with this path closed the quote and
        // appended two more commands. Now it is a filename, and an odd one at that.
        val hostile = """/sdcard"; rm -rf /sdcard/DCIM; echo """"
        val canonical = allowed(hostile, FsPolicy.Access.READ)?.canonical
        assertNotNull("metacharacters are not a reason to refuse", canonical)
        assertNull("argv carries it verbatim", FsPolicy.validateArgv(listOf("ls", "--", canonical!!)))
        assertTrue(canonical.contains(";"))
    }

    @Test
    fun `every flavour of injection attempt is accepted as a literal name`() {
        val names = listOf(
            "/sdcard/a\$(id)b", "/sdcard/a`id`b", "/sdcard/a|b", "/sdcard/a&&b",
            "/sdcard/a'b", "/sdcard/a\nb", "/sdcard/a\tb", "/sdcard/a b"
        )
        names.forEach { name ->
            val v = FsPolicy.vet(name, FsPolicy.Access.WRITE)
            assertTrue("$name should be allowed as a literal", v is FsPolicy.Verdict.Allow)
        }
    }

    @Test
    fun `NUL is rejected in a path and in argv`() {
        // execve truncates at a NUL, so an argument containing one is not the argument
        // the policy checked.
        assertTrue(denial("/sdcard/a\u0000b", FsPolicy.Access.READ)!!.contains("NUL"))
        assertNotNull(FsPolicy.validateArgv(listOf("rm", "--", "/sdcard/a\u0000b")))
    }

    @Test
    fun `argv rejects empty vectors and empty elements`() {
        assertNotNull(FsPolicy.validateArgv(emptyList()))
        assertNotNull(FsPolicy.validateArgv(listOf("ls", "--", "")))
        assertNull(FsPolicy.validateArgv(listOf("ls", "--", "/sdcard")))
    }

    @Test
    fun `relative paths are rejected, which also rules out leading-dash arguments`() {
        assertTrue(denial("sdcard/x", FsPolicy.Access.READ)!!.contains("relative"))
        assertTrue(denial("../../etc/passwd", FsPolicy.Access.READ)!!.contains("relative"))
        assertTrue(denial("-rf", FsPolicy.Access.READ)!!.contains("relative"))
        assertTrue(denial("", FsPolicy.Access.READ)!!.contains("empty"))
    }

    // ---- Defect 2: a well-formed command that should never have been issued ----

    @Test
    fun `app-private and system roots are denied for reads as well as writes`() {
        val forbidden = listOf(
            "/data/data/edu.pitt.isl.mobileclaw.notiftest/databases/mobileclaw.db",
            "/data/user/0/edu.pitt.isl.mobileclaw.notiftest/shared_prefs/x.xml",
            "/system/build.prop",
            "/data/local/tmp/mobileclaw_meminfo.json",
            "/proc/1/maps"
        )
        forbidden.forEach { path ->
            assertNotNull("$path must be denied for read", denial(path, FsPolicy.Access.READ))
            assertNotNull("$path must be denied for write", denial(path, FsPolicy.Access.WRITE))
        }
    }

    @Test
    fun `traversal out of shared storage is caught because the check runs after resolution`() {
        // The raw string starts with /sdcard and would pass a naive prefix test; only
        // canonicalisation reveals where it actually lands.
        val escape = "/sdcard/../data/data/edu.pitt.isl.mobileclaw.notiftest/databases"
        val reason = denial(escape, FsPolicy.Access.READ)
        assertNotNull(reason)
        assertTrue("the denial names the resolved path", reason!!.contains("/data/data"))
    }

    @Test
    fun `a symlink pointing out of shared storage is denied by its target`() {
        // Same class of bypass as `..`, but invisible in the string. Built for real
        // rather than mocked, because resolution is the behaviour under test.
        val outside = tmp.newFolder("private")
        val shared = tmp.newFolder("shared")
        val link = File(shared, "innocent")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())

        val resolved = (FsPolicy.vet(link.absolutePath, FsPolicy.Access.READ)
            as FsPolicy.Verdict.Allow).canonical
        assertEquals("vet reports the target, not the link", outside.canonicalPath, resolved)
    }

    @Test
    fun `writes are confined to shared storage while reads are broad`() {
        assertNull("reads outside shared storage are fine", denial("/etc/hosts", FsPolicy.Access.READ))
        assertNotNull("writes are not", denial("/etc/hosts", FsPolicy.Access.WRITE))
        assertNotNull(denial("/", FsPolicy.Access.WRITE))
        assertNotNull(FsPolicy.vet("/sdcard/Download/x.txt", FsPolicy.Access.WRITE) as? FsPolicy.Verdict.Allow)
        assertNotNull(FsPolicy.vet("/storage/emulated/0/DCIM", FsPolicy.Access.WRITE) as? FsPolicy.Verdict.Allow)
    }

    @Test
    fun `denylist matching respects path boundaries`() {
        // /systemx is a plausible name on shared storage and is not /system.
        assertNull(denial("/systemx/file", FsPolicy.Access.READ))
        assertNotNull(denial("/system", FsPolicy.Access.READ))
        assertNotNull(denial("/system/", FsPolicy.Access.READ))
    }

    // ---- Recursive-delete confirmations have to say what will be lost ----

    @Test
    fun `describeTree distinguishes trees the user would never confuse by path alone`() {
        val big = tmp.newFolder("big")
        File(big, "sub").mkdirs()
        repeat(3) { File(big, "sub/f$it.txt").writeText("hello") }
        val small = tmp.newFolder("small")
        File(small, "only.txt").writeText("x")

        val bigDesc = FsPolicy.describeTree(big.canonicalPath)
        val smallDesc = FsPolicy.describeTree(small.canonicalPath)
        assertTrue(bigDesc, bigDesc.startsWith("3 files in 1 directory"))
        assertTrue(smallDesc, smallDesc.startsWith("1 file in 0 directories"))
    }

    @Test
    fun `describeTree stops counting at the limit rather than walking a whole card`() {
        val dir = tmp.newFolder("many")
        repeat(10) { File(dir, "f$it").writeText("x") }
        val desc = FsPolicy.describeTree(dir.canonicalPath, limit = 4)
        assertTrue(desc, desc.startsWith("at least "))
    }

    @Test
    fun `describeTree degrades instead of throwing when the path is not visible`() {
        val desc = FsPolicy.describeTree("/sdcard/definitely-not-here")
        assertTrue(desc, desc.contains("unknown"))
    }
}
