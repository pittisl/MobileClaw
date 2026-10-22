package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing `ps -A -o RSS,NAME`.
 *
 * The sample lines here are copied verbatim from a device — column widths, the header
 * row, kernel threads in brackets, and all. A parser for another process's stdout is
 * exactly the kind of thing that works against the three lines its author had in mind
 * and then quietly drops half a real dump, so the fixture is real output rather than
 * something shaped to suit the parser.
 */
class PsOutputTest {

    /** Verbatim from `adb shell ps -A -o RSS,NAME`, including the header. */
    private val realOutput = """
           RSS NAME
          8832 init
             0 [kthreadd]
             0 [rcu_gp]
        229652 com.android.systemui
        252700 com.google.android.gms.persistent
        147356 com.google.android.gms.unstable
          6672 android.hardware.camera.provider@2.7-service-google
    """.trimIndent()

    @Test
    fun `real ps output parses to the app processes in it`() {
        val parsed = PsOutput.parse(realOutput)

        assertEquals(
            listOf(
                "com.android.systemui",
                "com.google.android.gms.persistent",
                "com.google.android.gms.unstable",
                "android.hardware.camera.provider@2.7-service-google"
            ),
            parsed.map { it.processName }
        )
        assertEquals(229652L, parsed.first { it.processName == "com.android.systemui" }.memoryKb)
    }

    @Test
    fun `the header row is not a process`() {
        // "RSS" doesn't parse as a number, which is the whole check — a parser that
        // skipped "line 0" instead would break the day a provider prints a banner.
        assertTrue(PsOutput.parse(realOutput).none { it.processName == "NAME" })
    }

    @Test
    fun `kernel threads are dropped rather than listed at zero`() {
        val parsed = PsOutput.parse(realOutput)
        assertTrue(parsed.none { it.processName.startsWith("[") })
        // `init` has real memory but is not something the user can act on, and has no
        // dot — the same test the collector script uses.
        assertTrue(parsed.none { it.processName == "init" })
    }

    @Test
    fun `a process name containing a space is not truncated`() {
        // NAME is last precisely so it can hold the rest of the line. Splitting on
        // whitespace into two fields would turn this into "Foo" and lose the rest,
        // which the user would read as a different app.
        val parsed = PsOutput.parse("  1234 com.example.app Helper Service")

        assertEquals("com.example.app Helper Service", parsed.single().processName)
        assertEquals(1234L, parsed.single().memoryKb)
    }

    @Test
    fun `a service suffix resolves to the package that kill_process can act on`() {
        val parsed = PsOutput.parse(" 4096 com.example.app:remote")

        assertEquals("com.example.app:remote", parsed.single().processName)
        assertEquals("com.example.app", parsed.single().packageName)
    }

    @Test
    fun `a truncated or garbled dump yields what it can rather than throwing`() {
        // Output is capped at 64 KB inside the privileged process, so the last line of
        // a big dump can be cut mid-field. Losing that row is fine; throwing is not —
        // it would turn a long process list into no memory data at all.
        val parsed = PsOutput.parse(
            """
             1024 com.example.good
            not-a-number com.example.bad

             2048 com.exam
            """.trimIndent()
        )

        assertEquals(listOf("com.example.good", "com.exam"), parsed.map { it.processName })
    }

    @Test
    fun `empty output is empty, not an exception`() {
        assertTrue(PsOutput.parse("").isEmpty())
        assertTrue(PsOutput.parse("\n\n").isEmpty())
    }

    @Test
    fun `a line with no name is skipped`() {
        assertNull(PsOutput.parse("  8832").firstOrNull())
        assertNull(PsOutput.parse("  8832   ").firstOrNull())
    }
}
