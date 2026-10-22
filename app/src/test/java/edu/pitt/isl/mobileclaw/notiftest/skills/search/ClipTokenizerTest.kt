package edu.pitt.isl.mobileclaw.notiftest.skills.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The Kotlin BPE against the reference `tokenizers` library's output. The
 * expected ids use HF `tokenizers` 0.2x with the same `tokenizer.json`; a tokenizer that drifts by one merge produces a text embedding that is
 * quietly a little wrong for every query, which no other test would notice.
 *
 * Reads the tokenizer the app ships (`app/src/main/assets/clip/tokenizer.json`, fetched
 * by `tools/fetch_clip_model.sh`); skips rather than fails on a checkout without it.
 */
class ClipTokenizerTest {

    companion object {
        private lateinit var tokenizer: ClipTokenizer

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = listOf("src/main/assets/clip/tokenizer.json", "app/src/main/assets/clip/tokenizer.json").map(::File).firstOrNull { it.exists() }
            assumeTrue("tokenizer.json not fetched (tools/fetch_clip_model.sh)", file != null)
            tokenizer = ClipTokenizer(file!!.readText())
        }
    }

    private fun ids(text: String) = tokenizer.encode(text).map { it.toInt() }

    @Test
    fun `plain phrases match the reference tokenizer`() {
        assertEquals(listOf(49406, 320, 1125, 539, 320, 2368, 49407), ids("a photo of a cat").take(7))
        assertEquals(listOf(49406, 320, 1125, 539, 320, 1929, 49407), ids("a photo of a dog").take(7))
        assertEquals(listOf(49406, 320, 1125, 539, 320, 2738, 30479, 49407), ids("a photo of a hotel receipt").take(8))
        assertEquals(listOf(49406, 12646, 539, 320, 3923, 49407), ids("screenshot of a map").take(6))
    }

    @Test
    fun `case, punctuation, accents, digits and whitespace follow the reference`() {
        assertEquals(listOf(49406, 2368, 256, 49407), ids("Cat!").take(4))
        assertEquals(listOf(49406, 15304, 29106, 7054, 4166, 273, 271, 273, 277, 49407), ids("café résumé 2026").take(10))
        assertEquals(listOf(49406, 5613, 588, 9442, 49407), ids("  weird   spacing  ").take(5))
        assertEquals(listOf(49406, 33781, 1642, 2857, 13093, 2076, 1561, 49407), ids("unbelievably supercalifragilistic").take(8))
    }

    @Test
    fun `the output is always 77 long, ends with EOS, pads with zero and truncates long text`() {
        val short = tokenizer.encode("cat")
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, short.size)
        assertEquals(ClipTokenizer.BOS.toLong(), short[0])
        assertEquals(ClipTokenizer.EOS.toLong(), short[2])
        assertTrue(short.drop(3).all { it == 0L })

        val long = tokenizer.encode("word ".repeat(200))
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, long.size)
        assertEquals("EOS survives truncation", ClipTokenizer.EOS.toLong(), long.last())
        assertTrue("no padding when full", long.none { it == 0L })
    }
}
