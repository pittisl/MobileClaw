package edu.pitt.isl.mobileclaw.notiftest.skills.search

/**
 * How the two retrieval routes become one list. Pure, so the rule can be argued
 * on the JVM without an index or a model.
 *
 * Word hits come first, in their own order: a file whose name, folder, text or label
 * contains the query is evidence of a kind a cosine score is not, and the model should
 * see it first. Similarity hits follow, best first, minus any path already listed; a
 * file found both ways keeps its place and gains its score, so the rendering can show
 * that the picture also *looks* like the query. Capped at [limit] overall — the
 * similarity route only fills what the word route left.
 */
object SearchMerge {

    fun merge(byWords: List<FileIndex.Hit>, bySimilarity: List<FileIndex.Hit>, limit: Int): List<FileIndex.Hit> {
        val scores = bySimilarity.associate { it.path to it.similarity }
        val out = ArrayList<FileIndex.Hit>(limit)
        for (h in byWords) {
            if (out.size >= limit) break
            out += if (h.similarity == null && scores[h.path] != null) h.copy(similarity = scores[h.path]) else h
        }
        val seen = out.mapTo(HashSet()) { it.path }
        for (h in bySimilarity.sortedByDescending { it.similarity ?: 0f }) {
            if (out.size >= limit) break
            if (seen.add(h.path)) out += h
        }
        return out
    }
}
