package edu.pitt.isl.mobileclaw.taskboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The colour rule and the tasks' JSON, on the JVM. */
class ThemeColorTest {

    @Test
    fun `names and hex literals are accepted, anything else refused`() {
        assertEquals("#6A1B9A", ThemeColor.parse("purple"))
        assertEquals("#6A1B9A", ThemeColor.parse("  Purple "))
        assertEquals("#546E7A", ThemeColor.parse("gray"))
        assertEquals("#ABCDEF", ThemeColor.parse("#abcdef"))
        assertEquals("#AABBCC", ThemeColor.parse("#abc"))
        assertNull(ThemeColor.parse("the colour of the sea"))
        assertNull(ThemeColor.parse("#12345"))
        assertNull(ThemeColor.parse(""))
    }

    @Test
    fun `tasks survive a round trip and garbage decodes to nothing`() {
        val tasks = listOf(Task(1, "buy cat food", "the salmon one", false), Task(2, "call mum", "", true))
        assertEquals(tasks, TaskJson.decode(TaskJson.encode(tasks)))
        assertEquals(emptyList<Task>(), TaskJson.decode("not json"))
    }
}
