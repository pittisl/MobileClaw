package edu.pitt.isl.mobileclaw.taskboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One task on the board. Plain data; [TaskBoardFunctions] converts it to the bus's shape. */
data class Task(val id: Int, val title: String, val note: String, val done: Boolean)

/**
 * The board's state, in `SharedPreferences` and nothing fancier: a few tasks, a colour,
 * and a log of the function calls that changed them. `SharedPreferences` for one reason
 * that matters to the demo — the AppFunction runs in the platform-driven service and the
 * screen is an Activity, and the preference-change listener is the cheapest way for the
 * screen to repaint the moment the agent's call lands, in the same process, with no
 * broadcast or database to arrange.
 */
class Board(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tasks(): List<Task> = TaskJson.decode(prefs.getString(K_TASKS, "[]") ?: "[]")

    fun themeColor(): String = prefs.getString(K_THEME, ThemeColor.DEFAULT) ?: ThemeColor.DEFAULT

    fun recentCalls(): List<String> = (prefs.getString(K_LOG, "") ?: "").lines().filter { it.isNotBlank() }

    @Synchronized
    fun addTask(title: String, note: String): Task {
        val all = tasks()
        val task = Task(id = (all.maxOfOrNull { it.id } ?: 0) + 1, title = title.trim(), note = note.trim(), done = false)
        write(all + task)
        return task
    }

    /** Marks a task done; null when there is no such id, which the function turns into an error the caller can read. */
    @Synchronized
    fun completeTask(id: Int): Task? {
        val all = tasks()
        val task = all.firstOrNull { it.id == id } ?: return null
        val done = task.copy(done = true)
        write(all.map { if (it.id == id) done else it })
        return done
    }

    @Synchronized
    fun setThemeColor(hex: String) {
        prefs.edit().putString(K_THEME, hex).apply()
    }

    /** Records a function call on the board, newest last, bounded — the screen shows the last few. */
    @Synchronized
    fun logCall(line: String) {
        val stamped = "${TIME.format(Date())} $line"
        val kept = (recentCalls() + stamped).takeLast(MAX_LOG)
        prefs.edit().putString(K_LOG, kept.joinToString("\n")).apply()
    }

    /** Back to nothing — between two visitors at the booth. */
    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
    }

    /** Whether [listener] sees changes; the Activity registers it for the life of its screen. */
    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener, on: Boolean) {
        if (on) prefs.registerOnSharedPreferenceChangeListener(listener)
        else prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun write(tasks: List<Task>) {
        prefs.edit().putString(K_TASKS, TaskJson.encode(tasks)).apply()
    }

    private companion object {
        const val PREFS = "board"
        const val K_TASKS = "tasks"
        const val K_THEME = "theme"
        const val K_LOG = "log"
        const val MAX_LOG = 8
        val TIME = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    }
}

/** The tasks' JSON shape, kept apart from the store so a JVM test can hold it. */
object TaskJson {
    fun encode(tasks: List<Task>): String = JSONArray().also { arr ->
        tasks.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("title", t.title).put("note", t.note).put("done", t.done))
        }
    }.toString()

    fun decode(json: String): List<Task> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(o.getInt("id"), o.getString("title"), o.optString("note", ""), o.optBoolean("done", false))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
