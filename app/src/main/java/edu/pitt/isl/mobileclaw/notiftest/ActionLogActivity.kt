package edu.pitt.isl.mobileclaw.notiftest

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Shows [ActionLogStore] live, newest first, including reasons why a confirmation
 * expired or could not execute after process death.
 *
 * Read-only and user-initiated: this screen is not a [Tool], is not in [ToolRegistry],
 * and does not route through [PendingAction]. Bulk audit history is sensitive; any
 * agent-facing history reader would need its own output bound and redaction boundary.
 *
 * [ActionLogStore.record] redacts before writing, so the screen displays the stored
 * record without applying a second transformation.
 */
class ActionLogActivity : AppCompatActivity() {

    private lateinit var adapter: ActionLogAdapter
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var failuresOnly: CheckBox

    /** Newest first — [ActionLogStore.entries] is oldest first, as it always has been. */
    private var observed: List<ActionLogStore.ActionLogEntry> = emptyList()

    /** Rows on disk, which is the larger number whenever the window is full. */
    private var total = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_action_log)
        // Secondary screens run under a NoActionBar theme, so nothing supplied an up
        // affordance and the system gesture was the only way out — fine if you know it,
        // a dead end if you do not, which is every visitor handed the phone at a booth.
        findViewById<android.widget.TextView>(R.id.headerTitle).text = "Action history"
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)
        failuresOnly = findViewById(R.id.failuresOnly)

        adapter = ActionLogAdapter()
        list = findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@ActionLogActivity)
            adapter = this@ActionLogActivity.adapter
        }

        failuresOnly.setOnCheckedChangeListener { _, _ -> render() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ActionLogStore.entries.collect { entries ->
                    observed = entries.asReversed()
                    // Re-read per emission rather than once: the retention sweep and
                    // "clear action log" both change the total without this screen
                    // being reopened.
                    total = ActionLogStore.total()
                    render()
                }
            }
        }
    }

    private fun render() {
        val onlyFailures = failuresOnly.isChecked
        val shown = if (onlyFailures) observed.filter { !it.success } else observed
        adapter.submitList(shown)

        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            observed.isNotEmpty() && onlyFailures ->
                "No failed actions in the most recent ${observed.size}."
            else ->
                "Nothing here yet.\n\nEvery action the agent takes — a notification " +
                    "button pressed, a reply sent, a privileged command run, or a " +
                    "confirmation that expired unanswered — is recorded here."
        }

        summary.text = buildSummary()
    }

    /**
     * The window is [ActionLogStore.OBSERVED_ENTRIES]; retention keeps up to 5000 rows
     * for 90 days. Naming both numbers is the point — an audit trail that shows a
     * truncated list without saying so invites the conclusion that nothing else
     * happened.
     */
    private fun buildSummary(): String {
        val failed = observed.count { !it.success }
        return if (total > observed.size) {
            "Most recent ${observed.size} of $total actions · $failed of these failed " +
                "· export for the full record"
        } else {
            "$total ${if (total == 1) "action" else "actions"} · $failed failed"
        }
    }
}
