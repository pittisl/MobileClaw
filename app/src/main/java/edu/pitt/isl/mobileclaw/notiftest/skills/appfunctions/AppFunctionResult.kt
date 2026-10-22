package edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions

/**
 * Turns what `cmd app_function execute-app-function` prints into what the model should
 * read. Pure Kotlin so the shapes below are a JVM test.
 *
 * The shell command prints the return value as the platform's `GenericDocument` debug
 * dump — `{ namespace: "", id: "", score: 0, schemaType: "…", properties: { … } }`,
 * nested for objects and lists — which is neither JSON nor anything a model reads well:
 * a one-word answer can span many lines. The
 * parser walks the dump, finds the return
 * value under `androidAppfunctionsReturnValue`, drops the document plumbing (namespace,
 * id, score, schema type, timestamps) and renders the values as `key: value` lines.
 * Anything it does not understand comes back verbatim, so a new shape is ugly rather
 * than lost.
 *
 * Some Android 16 devices print plain JSON instead:
 * `{"androidAppfunctionsReturnValue": [ { "themeColor": ["#1565C0"], … } ]}`, with no
 * success line and no document plumbing — every key is the function's own, including
 * `id`. The same reader parses it; only the plumbing filter is switched off.
 */
object AppFunctionResult {

    /** Transport exit 0 alone does not establish a successful platform invocation. */
    fun executionConfirmed(raw: String): Boolean {
        val text = raw.trim()
        if (text.startsWith(SUCCESS)) return true
        if (!text.startsWith("{")) return false
        val value = try { Parser(text).value() } catch (_: Exception) { return false }
        return value is Map<*, *> && value.containsKey(VALUE_KEY)
    }

    private const val SUCCESS = "App function executed successfully."
    private const val RETURN = "Function return:"
    private const val VALUE_KEY = "androidAppfunctionsReturnValue"
    private val PLUMBING = setOf("namespace", "id", "score", "schemaType", "creationTimestampMillis", "timeToLiveMillis")

    /**
     * `android.app.appfunctions.AppFunctionException` codes as observed on Android 16 —
     * 1001 for a Jetpack `AppFunctionInvalidArgumentException`, 1002 for a disabled
     * function, 1500 for `AppFunctionElementNotFoundException`; the rest from the platform's constants.
     */
    private val ERROR_CODES = mapOf(
        1000 to "the caller is not allowed to call this function",
        1001 to "an argument was invalid (the app rejected the parameters)",
        1002 to "the app function is disabled",
        1003 to "the app has no such function",
        1004 to "a resource the call named was not found",
        1500 to "the item the call named was not found",
        2000 to "a system error occurred",
        2001 to "the call was cancelled",
        3000 to "the app hit an error of its own",
    )

    /** What the model reads for the output of calling [function] (the `Class#method` id). */
    fun render(function: String, raw: String): String {
        val name = function.substringAfterLast('#')
        val text = raw.trim()
        if (text.startsWith("Error executing app function")) {
            // Two formats seen: "Error executing app function: 1002 - android.app.appfunctions.AppFunctionException: The app function is disabled"
            // (Pixel) and "…: android.app.appfunctions.AppFunctionException: The app function is disabled (code 1002). See logcat…"
            // (OnePlus). A message the *callee* threw does not survive at all — the platform
            // forwards only the code — so the code is translated where it is known, and a
            // callee that wants the caller to read a reason has to return it, not throw it.
            val after = text.substringAfter(':').trim()
            val code = Regex("^(\\d{4})\\s*-").find(after)?.groupValues?.get(1)?.toInt()
                ?: Regex("\\(code (\\d{4})\\)").find(after)?.groupValues?.get(1)?.toInt()
            val message = after.substringAfter("AppFunctionException", "").removePrefix(":").trim()
                .removeSuffix("See logcat for more details.").trim().removeSuffix(".").trim()
                .replace(Regex("\\s*\\(code \\d{4}\\)$"), "")
            val reason = when {
                message.isNotEmpty() && !message.startsWith("(code") -> message
                code != null -> ERROR_CODES[code] ?: "error code $code"
                else -> after
            }
            return "$name failed: $reason"
        }
        if (text.startsWith("{")) {
            // The JSON shape (OnePlus): the return value sits at the top level and nothing
            // in it is plumbing.
            val tree = try { Parser(text).value() } catch (_: Exception) { null } ?: return text
            val value = (tree as? Map<*, *>)?.let { if (it.containsKey(VALUE_KEY)) it[VALUE_KEY] else null } ?: return text
            return "$name returned: " + renderValue(value, plumbing = false)
        }
        if (!text.startsWith(SUCCESS)) return text
        val dump = text.substringAfter(RETURN, "").trim()
        if (dump.isEmpty()) return "$name succeeded with no return value."
        val tree = try { Parser(dump).value() } catch (_: Exception) { null } ?: return text
        val value = (tree as? Map<*, *>)?.get("properties")?.let { (it as? Map<*, *>)?.get(VALUE_KEY) } ?: return text
        return "$name returned: " + renderValue(value, plumbing = true)
    }

    private fun renderValue(v: Any?, plumbing: Boolean): String = when (v) {
        is List<*> -> when {
            v.isEmpty() -> "(empty list)"
            v.size == 1 && v[0] !is Map<*, *> -> scalar(v[0])
            v.all { it !is Map<*, *> } -> v.joinToString(", ") { scalar(it) }
            else -> "\n" + v.mapIndexed { i, item -> renderItem(if (v.size > 1) "${i + 1}. " else "", item, plumbing) }.joinToString("\n")
        }
        is Map<*, *> -> "\n" + renderItem("", v, plumbing)
        else -> scalar(v)
    }

    /**
     * One object: its properties as indented `key: value` lines. With [plumbing] the item
     * is a `GenericDocument` whose own fields sit under `properties` and whose top-level
     * keys (namespace, id, …) are the platform's; without it every key is the function's.
     */
    private fun renderItem(prefix: String, item: Any?, plumbing: Boolean): String {
        val doc = item as? Map<*, *> ?: return "$prefix${scalar(item)}"
        // Plumbing is filtered at the document level only: inside `properties` every key
        // is the function's own, and a task really does have a field called "id".
        val props = if (plumbing) (doc["properties"] as? Map<*, *>)?.entries ?: doc.entries.filter { it.key !in PLUMBING }
                    else doc.entries
        val lines = props.map { (k, value) ->
            val shown = when (value) {
                is List<*> -> when {
                    value.isEmpty() -> "(none)"
                    value.all { it !is Map<*, *> } -> value.joinToString(", ") { scalar(it) }
                    else -> value.joinToString("; ") { renderItem("", it, plumbing).replace("\n", ", ").trim() }
                }
                else -> scalar(value)
            }
            "$k: $shown"
        }
        if (lines.isEmpty()) return "$prefix(empty)"
        val indent = " ".repeat(prefix.length)
        return lines.mapIndexed { i, l -> (if (i == 0) prefix else indent) + l }.joinToString("\n")
    }

    private fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        else -> v.toString()
    }

    /**
     * A recursive-descent reader for the dump's grammar: objects `{ key: value, … }` with
     * bare or quoted keys, lists `[ … ]`, quoted strings with backslash escapes, numbers,
     * `true`/`false`/`null`. Trailing commas and blank lines are tolerated, since the dump
     * has both.
     */
    private class Parser(private val s: String) {
        private var i = 0

        fun value(): Any? {
            ws()
            return when {
                i >= s.length -> throw IllegalStateException("eof")
                s[i] == '{' -> obj()
                s[i] == '[' -> list()
                s[i] == '"' -> str()
                else -> bare()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                ws()
                if (peek() == '}') { i++; return out }
                val key = if (peek() == '"') str() else ident()
                ws(); expect(':')
                out[key] = value()
                ws()
                if (peek() == ',') i++
            }
        }

        private fun list(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            while (true) {
                ws()
                if (peek() == ']') { i++; return out }
                out += value()
                ws()
                if (peek() == ',') i++
            }
        }

        /**
         * The dump does not escape quotes inside a string — a return value of `"sea" is not
         * a colour` prints as `[""sea" is not a colour"]` (measured) — so a quote ends the
         * string only when what follows it, past whitespace, is a delimiter. Inside a
         * value, a quote followed by a letter is just a quote.
         */
        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j >= s.length || s[j] in ",]}:") break
                    sb.append(c); i++; continue
                }
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(s[i]) }
                } else sb.append(c)
                i++
            }
            expect('"')
            return sb.toString()
        }

        private fun ident(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.' || s[i] == '$')) i++
            if (start == i) throw IllegalStateException("identifier expected at $i")
            return s.substring(start, i)
        }

        private fun bare(): Any? {
            val start = i
            while (i < s.length && s[i] !in ",}] \n\r\t") i++
            val t = s.substring(start, i)
            return when {
                t == "true" -> true
                t == "false" -> false
                t == "null" -> null
                t.toLongOrNull() != null -> t.toLong()
                t.toDoubleOrNull() != null -> t.toDouble()
                else -> t
            }
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char = if (i < s.length) s[i] else ' '
        private fun expect(c: Char) { ws(); if (peek() != c) throw IllegalStateException("expected $c at $i"); i++ }
    }
}
