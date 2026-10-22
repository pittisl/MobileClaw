package edu.pitt.isl.mobileclaw.notiftest.skills.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The app-side read route for `file_system`. What matters is that it
 * behaves like the bridge's toybox commands where the model would notice — a
 * directory handed to `cat`, a missing path, a glob — and that nothing here can grow
 * without bound: `cat` is capped and `find` stops.
 */
class DirectFileReadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, text: String): File = tmp.newFile(name).apply { writeText(text) }

    @Test
    fun `ls lists names sorted, and -la adds size and date`() {
        file("b.txt", "bb"); file("a.txt", "a")
        tmp.newFolder("dir")
        assertEquals("a.txt\nb.txt\ndir", DirectFileReads.ls(tmp.root.path, long = false))
        val long = DirectFileReads.ls(tmp.root.path, long = true)
        assertTrue(long, long.lines().first().startsWith("-r") && long.lines().first().endsWith("a.txt"))
        assertTrue(long, long.lines().last().startsWith("dr"))
    }

    @Test
    fun `cat reads a file and refuses a directory like the shell does`() {
        file("note.txt", "hello\nworld")
        assertEquals("hello\nworld", DirectFileReads.cat(File(tmp.root, "note.txt").path))
        assertTrue(DirectFileReads.cat(tmp.root.path).endsWith("Is a directory"))
        assertTrue(DirectFileReads.cat(File(tmp.root, "nope").path).endsWith("No such file or directory"))
    }

    @Test
    fun `cat is capped and says so`() {
        file("big.log", "x".repeat(DirectFileReads.MAX_CAT_CHARS + 5_000))
        val out = DirectFileReads.cat(File(tmp.root, "big.log").path)
        assertTrue(out.length < DirectFileReads.MAX_CAT_CHARS + 200)
        assertTrue(out, out.contains("output truncated"))
    }

    @Test
    fun `stat names the type and size`() {
        file("s.txt", "12345")
        val out = DirectFileReads.stat(File(tmp.root, "s.txt").path)
        assertTrue(out, out.contains("Size: 5") && out.contains("regular file"))
        assertTrue(DirectFileReads.stat(tmp.root.path).contains("directory"))
    }

    @Test
    fun `find matches a glob at every depth and stops at the cap`() {
        val sub = tmp.newFolder("sub")
        file("top.txt", ""); File(sub, "deep.txt").writeText(""); File(sub, "other.md").writeText("")
        val out = DirectFileReads.find(tmp.root.path, "*.txt").lines()
        assertEquals(2, out.size)
        assertTrue(out.any { it.endsWith("top.txt") } && out.any { it.endsWith("sub/deep.txt") })
        assertFalse(out.any { it.endsWith("other.md") })

        val many = tmp.newFolder("many")
        repeat(DirectFileReads.MAX_FIND_RESULTS + 20) { File(many, "f$it.dat").writeText("") }
        val capped = DirectFileReads.find(many.path, "*.dat")
        assertTrue(capped, capped.endsWith("[stopped after ${DirectFileReads.MAX_FIND_RESULTS} matches]"))
    }

    @Test
    fun `globs are literal apart from star and question mark`() {
        assertTrue(DirectFileReads.globToRegex("*.txt").matches("a.txt"))
        assertFalse(DirectFileReads.globToRegex("*.txt").matches("a.txt.bak"))
        assertTrue(DirectFileReads.globToRegex("IMG_????.jpg").matches("IMG_0001.jpg"))
        assertFalse("a dot is a dot, not any character", DirectFileReads.globToRegex("a.b").matches("axb"))
    }
}
