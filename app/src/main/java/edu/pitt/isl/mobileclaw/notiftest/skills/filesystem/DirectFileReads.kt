package edu.pitt.isl.mobileclaw.notiftest.skills.filesystem

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app-side read-only route for `file_system` when all-files access is granted
 * and no privileged backend is available. Only `ls`, `cat`, `stat` and `find` use
 * this route; modifying operations remain behind `ShellGate.modify`.
 *
 * Paths arrive canonicalised and vetted by [edu.pitt.isl.mobileclaw.notiftest.FsPolicy].
 * This object reads them without defining a second policy. Its output follows the
 * toybox command shapes so the model can handle either route consistently.
 *
 * Pure JVM so directories passed to `cat`, oversized files and unreadable entries
 * can be tested with a temporary folder.
 */
object DirectFileReads {

    /** Most of a file `cat` returns; the tail is replaced by a notice. Same order as the bridge's output cap. */
    const val MAX_CAT_CHARS = 16_000

    /** `find` stops here — a pattern like `*` over a whole card is not something the model should read. */
    const val MAX_FIND_RESULTS = 500

    fun ls(canonical: String, long: Boolean): String {
        val f = File(canonical)
        if (!f.exists()) return "ls: $canonical: No such file or directory"
        if (!f.isDirectory) return if (long) longLine(f) else f.name
        val children = f.listFiles() ?: return "ls: $canonical: Permission denied"
        if (children.isEmpty()) return ""
        val sorted = children.sortedBy { it.name.lowercase(Locale.ROOT) }
        return if (long) sorted.joinToString("\n") { longLine(it) } else sorted.joinToString("\n") { it.name }
    }

    fun cat(canonical: String): String {
        val f = File(canonical)
        if (!f.exists()) return "cat: $canonical: No such file or directory"
        if (f.isDirectory) return "cat: $canonical: Is a directory"
        return try {
            val text = readHead(f, MAX_CAT_CHARS + 1)
            if (text.length > MAX_CAT_CHARS) {
                text.take(MAX_CAT_CHARS) + "\n[output truncated at $MAX_CAT_CHARS characters; file is ${f.length()} bytes]"
            } else text
        } catch (e: IOException) {
            "cat: $canonical: ${e.message ?: "read failed"}"
        }
    }

    fun stat(canonical: String): String {
        val f = File(canonical)
        if (!f.exists()) return "stat: $canonical: No such file or directory"
        val type = when {
            f.isDirectory -> "directory"
            f.isFile -> "regular file"
            else -> "other"
        }
        return "  File: $canonical\n  Size: ${f.length()}\tType: $type\n" +
            "Modify: ${DATE.format(Date(f.lastModified()))}\nAccess: ${perms(f)}"
    }

    /**
     * `find <path> -name <pattern>`: a glob on the base name, matched at every depth,
     * the directory itself included as `find` does. Symlinked directories are not
     * followed, for the same loop-and-escape reason the indexer gives.
     */
    fun find(canonical: String, pattern: String): String {
        val root = File(canonical)
        if (!root.exists()) return "find: $canonical: No such file or directory"
        val regex = globToRegex(pattern)
        val out = ArrayList<String>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty() && out.size < MAX_FIND_RESULTS) {
            val cur = stack.removeLast()
            if (regex.matches(cur.name)) out += cur.path
            if (cur.isDirectory && !isSymlink(cur)) {
                cur.listFiles()?.sortedByDescending { it.name }?.forEach { stack.add(it) }
            }
        }
        val listing = out.joinToString("\n")
        return if (out.size >= MAX_FIND_RESULTS) "$listing\n[stopped after $MAX_FIND_RESULTS matches]" else listing
    }

    // ---- helpers ----

    private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private fun longLine(f: File): String =
        "${if (f.isDirectory) "d" else "-"}${perms(f)} ${f.length().toString().padStart(9)} ${DATE.format(Date(f.lastModified()))} ${f.name}"

    private fun perms(f: File): String =
        (if (f.canRead()) "r" else "-") + (if (f.canWrite()) "w" else "-") + (if (f.canExecute()) "x" else "-")

    private fun isSymlink(f: File): Boolean = try {
        f.canonicalFile != f.absoluteFile.let { File(it.parentFile?.canonicalPath ?: "/", it.name) }
    } catch (_: IOException) {
        true
    }

    private fun readHead(f: File, chars: Int): String {
        val cap = chars * 4 // UTF-8 worst case per char kept
        val buf = ByteArray(minOf(cap.toLong(), f.length().coerceAtLeast(1)).toInt())
        var read = 0
        f.inputStream().use { input ->
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
        }
        return String(buf, 0, read, Charsets.UTF_8).take(chars)
    }

    /** `*` and `?` only, like `find -name`; everything else is literal. */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.append('$').toString())
    }
}
