package edu.pitt.isl.mobileclaw.notiftest

/** Ignore the shade left by the last visitor, without losing a new SMS on the same key. */
internal class DemoEventBaseline {
    private val posts = mutableMapOf<String, Long>()

    fun reset(active: List<Pair<String, Long>>) {
        posts.clear()
        posts.putAll(active)
    }

    fun shouldIgnore(key: String, postTime: Long, posted: Boolean): Boolean {
        val old = posts[key] ?: return false
        if (!posted) {
            // A delayed removal for the old post must not discard a newer baseline.
            if (old == postTime) posts.remove(key)
            return true
        }
        if (old == postTime) return true
        posts.remove(key)
        return false
    }
}
