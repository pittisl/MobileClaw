package edu.pitt.isl.mobileclaw.notiftest.skills.search

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * CLIP's byte-level BPE tokenizer, from the `tokenizer.json` that ships with
 * the MobileCLIP weights, in plain Kotlin so it has no Android import and the JVM test
 * can hold it to the reference tokenizer's output token for token.
 *
 * What it reproduces, in order, is exactly the HF `tokenizers` pipeline the file
 * describes: NFC, whitespace runs to one space, lower-case; the CLIP split regex; each
 * piece's UTF-8 bytes mapped to the printable code points the vocabulary is written in;
 * BPE by merge rank with `</w>` on a word's last symbol; then `<|startoftext|>` … `<|endoftext|>`,
 * truncated to and padded to [CONTEXT_LENGTH] — padded, because the exported text model
 * takes a fixed 77 and rejects anything shorter. The pad id is 0 ("!"),
 * per the tokenizer config; the model pools at the end-of-text token so the padding
 * value only has to be smaller than it.
 *
 * Not a general tokenizer: no dropout, no unknown-token path beyond skipping (every byte
 * is in the vocabulary, so a word can always be spelled), no decoding.
 */
class ClipTokenizer(tokenizerJson: String) {

    private val vocab: Map<String, Int>
    private val ranks: Map<String, Int>
    private val cache = HashMap<String, IntArray>()

    init {
        val model = JSONObject(tokenizerJson).getJSONObject("model")
        val v = model.getJSONObject("vocab")
        vocab = HashMap<String, Int>(v.length() * 2).also { m -> v.keys().forEach { k -> m[k] = v.getInt(k) } }
        val merges = model.getJSONArray("merges")
        ranks = HashMap<String, Int>(merges.length() * 2).also { m ->
            for (i in 0 until merges.length()) {
                // Either "a b" (older files) or ["a", "b"] (newer); accept both.
                val e = merges.get(i)
                m[if (e is String) e else (e as org.json.JSONArray).let { "${it.getString(0)} ${it.getString(1)}" }] = i
            }
        }
    }

    /** Token ids for [text], with BOS/EOS, exactly [CONTEXT_LENGTH] long. */
    fun encode(text: String): LongArray {
        val ids = ArrayList<Int>(CONTEXT_LENGTH)
        ids += BOS
        for (piece in split(normalise(text))) {
            for (id in bpe(piece)) {
                if (ids.size >= CONTEXT_LENGTH - 1) break
                ids += id
            }
            if (ids.size >= CONTEXT_LENGTH - 1) break
        }
        ids += EOS
        return LongArray(CONTEXT_LENGTH) { i -> if (i < ids.size) ids[i].toLong() else PAD.toLong() }
    }

    /** Raw token ids without BOS/EOS or padding — what the reference tokenizer's `ids` are, minus its ends. */
    internal fun tokens(text: String): List<Int> = split(normalise(text)).flatMap { bpe(it).toList() }

    private fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    private fun split(text: String): List<String> = SPLIT.findAll(text).map { it.value }.toList()

    private fun bpe(piece: String): IntArray = cache.getOrPut(piece) {
        // Bytes to the vocabulary's alphabet, end-of-word marker on the last symbol.
        val symbols = ArrayList<String>()
        for (b in piece.toByteArray(Charsets.UTF_8)) symbols += BYTE_TO_CHAR[b.toInt() and 0xff]
        if (symbols.isEmpty()) return@getOrPut IntArray(0)
        symbols[symbols.size - 1] = symbols[symbols.size - 1] + END_OF_WORD

        while (symbols.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until symbols.size - 1) {
                val r = ranks["${symbols[i]} ${symbols[i + 1]}"] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            symbols[best] = symbols[best] + symbols[best + 1]
            symbols.removeAt(best + 1)
        }
        symbols.mapNotNull { vocab[it] }.toIntArray()
    }

    companion object {
        const val CONTEXT_LENGTH = 77
        const val BOS = 49406
        const val EOS = 49407
        const val PAD = 0
        private const val END_OF_WORD = "</w>"

        private val WHITESPACE = Regex("\\s+")
        private val SPLIT = Regex("'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+")

        /**
         * GPT-2's byte-to-unicode table: printable ASCII and Latin-1 map to themselves,
         * the rest to code points from 256 up, so every byte is a visible character and
         * the vocabulary file needs no escaping.
         */
        private val BYTE_TO_CHAR: Array<String> = run {
            val direct = (33..126) + (161..172) + (174..255)
            val table = arrayOfNulls<String>(256)
            for (b in direct) table[b] = b.toChar().toString()
            var next = 256
            for (b in 0..255) if (table[b] == null) table[b] = (next++).toChar().toString()
            @Suppress("UNCHECKED_CAST")
            table as Array<String>
        }
    }
}
