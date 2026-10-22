package edu.pitt.isl.mobileclaw.taskboard

import java.util.Locale

/**
 * What `setThemeColor` accepts, decided without a device: a colour name from the short
 * list below or a `#RRGGBB` / `#RGB` literal. Names, because that is what a person says
 * to an assistant ("make it purple"); a fixed list, because "make it the colour of the
 * sea" is the model's problem to turn into one of these, not this app's. Pure Kotlin so
 * the rule is a JVM test.
 */
object ThemeColor {

    const val DEFAULT = "#1565C0"

    val NAMED: Map<String, String> = linkedMapOf(
        "blue" to "#1565C0",
        "red" to "#D32F2F",
        "orange" to "#EF6C00",
        "yellow" to "#F9A825",
        "green" to "#2E7D32",
        "teal" to "#00796B",
        "purple" to "#6A1B9A",
        "pink" to "#AD1457",
        "grey" to "#546E7A",
        "gray" to "#546E7A",
        "black" to "#212121",
    )

    private val HEX6 = Regex("#[0-9a-fA-F]{6}")
    private val HEX3 = Regex("#[0-9a-fA-F]{3}")

    /** The `#RRGGBB` for [input], upper-cased, or null when it is neither a known name nor a hex literal. */
    fun parse(input: String): String? {
        val s = input.trim().lowercase(Locale.ROOT)
        NAMED[s]?.let { return it }
        if (HEX6.matches(s)) return s.uppercase(Locale.ROOT)
        if (HEX3.matches(s)) return "#" + s.drop(1).map { "$it$it" }.joinToString("").uppercase(Locale.ROOT)
        return null
    }

    /** The names, for an error message and for the function's own documentation. */
    fun names(): String = NAMED.keys.filter { it != "gray" }.joinToString(", ")
}
