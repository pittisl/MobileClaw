package edu.pitt.isl.mobileclaw.notiftest.skills.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that turns two result lists into one: words first, similarity fills,
 * no path twice, a file found both ways keeps its place and gains its score.
 */
class SearchMergeTest {

    private fun hit(name: String, similarity: Float? = null) =
        FileIndex.Hit(name, "/storage/emulated/0/DCIM/$name", IndexPolicy.Kind.IMAGE, 1, 1, null, null, similarity)

    @Test
    fun `word hits lead and similarity fills the rest best first`() {
        val words = listOf(hit("a.jpg"), hit("b.jpg"))
        val similar = listOf(hit("c.jpg", 0.21f), hit("d.jpg", 0.30f))
        assertEquals(listOf("a.jpg", "b.jpg", "d.jpg", "c.jpg"), SearchMerge.merge(words, similar, 8).map { it.name })
    }

    @Test
    fun `a file found both ways appears once, where the words put it, with its score`() {
        val out = SearchMerge.merge(listOf(hit("a.jpg"), hit("b.jpg")), listOf(hit("b.jpg", 0.25f), hit("z.jpg", 0.20f)), 8)
        assertEquals(listOf("a.jpg", "b.jpg", "z.jpg"), out.map { it.name })
        assertNull(out[0].similarity)
        assertEquals(0.25f, out[1].similarity!!, 0.0001f)
    }

    @Test
    fun `the limit caps the merged list and word hits are never displaced`() {
        val words = (1..5).map { hit("w$it.jpg") }
        val similar = (1..5).map { hit("s$it.jpg", 0.3f) }
        val out = SearchMerge.merge(words, similar, 3)
        assertEquals(listOf("w1.jpg", "w2.jpg", "w3.jpg"), out.map { it.name })
    }

    @Test
    fun `either side may be empty`() {
        assertEquals(listOf("s.jpg"), SearchMerge.merge(emptyList(), listOf(hit("s.jpg", 0.2f)), 8).map { it.name })
        assertEquals(listOf("w.jpg"), SearchMerge.merge(listOf(hit("w.jpg")), emptyList(), 8).map { it.name })
    }
}
