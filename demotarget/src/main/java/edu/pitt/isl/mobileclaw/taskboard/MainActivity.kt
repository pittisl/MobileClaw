package edu.pitt.isl.mobileclaw.taskboard

import android.Manifest
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The board on screen. It has no controls of its own beyond a reset: everything that
 * changes here is changed by a function call from outside, which is the point — the
 * visitor watches this screen while MobileClaw, on the same phone or the next one,
 * drives it. Repaints on every preference change, so the theme flips and the task
 * appears the instant the call lands.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var board: Board
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { render() } }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        board = Board(this)
        Notifier.ensureChannel(this)
        findViewById<Button>(R.id.reset).setOnClickListener { board.reset(); render() }
        // Ask once, up front: postNotification called before this is granted answers with
        // an honest "not allowed" rather than a silently dropped notification.
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        board.observe(listener, true)
        render()
    }

    override fun onPause() {
        board.observe(listener, false)
        super.onPause()
    }

    private fun render() {
        val colour = board.themeColor()
        findViewById<LinearLayout>(R.id.header).setBackgroundColor(Color.parseColor(colour))
        val tasks = board.tasks()
        findViewById<TextView>(R.id.summary).text =
            "theme $colour · ${tasks.count { !it.done }} open, ${tasks.count { it.done }} done"
        val rows = tasks.map { t -> (if (t.done) "☑ " else "☐ ") + t.title + (if (t.note.isNotBlank()) "  —  ${t.note}" else "") }
        findViewById<ListView>(R.id.tasks).adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.ifEmpty { listOf("(no tasks yet)") })
        findViewById<TextView>(R.id.log).text = board.recentCalls().joinToString("\n").ifEmpty { "(none yet)" }
    }
}
