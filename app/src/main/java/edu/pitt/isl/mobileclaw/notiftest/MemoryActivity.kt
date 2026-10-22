package edu.pitt.isl.mobileclaw.notiftest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows [MemoryStore] — what the agent believes about the user — grouped by provenance,
 * newest first, with a per-row delete.
 *
 * Same rule that produced [ActionLogActivity]: a store the user cannot inspect is one they
 * cannot correct. **This one is worse than the audit trail**, and that is why it ships
 * alongside the store rather than after it — the audit trail records what already
 * happened, while this shapes what the assistant does next. A wrong memory is not a wrong
 * entry in a list; it is a wrong belief that will be re-injected into every later
 * conversation until someone deletes it.
 *
 * **Read-and-delete only, and not a [Tool].** Not in [ToolRegistry], nothing through
 * [PendingAction] — the same reasoning as [ActionLogActivity] and [DataExport].
 *
 * The one thing this screen must never do is present a redacted memory as though it were
 * kept whole. By design, this is the only table in the app that can hold unredacted
 * sensitive text, so which rows those are is exactly what a user is here to find out.
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var adapter: MemoryAdapter
    private lateinit var summary: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        // Secondary screens run under a NoActionBar theme, so nothing supplied an up
        // affordance and the system gesture was the only way out — fine if you know it,
        // a dead end if you do not, which is every visitor handed the phone at a booth.
        findViewById<android.widget.TextView>(R.id.headerTitle).text = "What the agent remembers"
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)

        adapter = MemoryAdapter(::confirmForget, ::togglePin)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MemoryActivity)
            adapter = this@MemoryActivity.adapter
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MemoryStore.memories.collect { render(it) }
            }
        }
    }

    /**
     * Deleting a memory is confirmed, unlike writing one.
     *
     * Not an inconsistency with the memory-write policy: writing is reversible from this very screen, while
     * deleting is not reversible from anywhere. The memory's text is shown in the dialog
     * because "Forget this memory?" with nothing else on screen is a question nobody can
     * answer.
     */
    private fun confirmForget(memory: MemoryStore.Memory) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Forget this?")
            .setMessage("\"${memory.content}\"\n\nThis cannot be undone.")
            .setPositiveButton("Forget") { _, _ ->
                lifecycleScope.launch { MemoryStore.forget(memory.id) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Pinning is not confirmed, unlike forgetting: it is reversible by pressing the same
     * button again, and the row redraws to show the new state. The memory-write policy's rule about not
     * training the user to tap through dialogs applies here too.
     */
    private fun togglePin(memory: MemoryStore.Memory) {
        lifecycleScope.launch { MemoryStore.setPinned(memory.id, !memory.pinned) }
    }

    private fun render(memories: List<MemoryStore.Memory>) {
        adapter.submitList(memories)

        empty.visibility = if (memories.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "Nothing remembered yet.\n\nAsk the agent to remember something — " +
            "a preference, a name, a standing instruction — and it will appear here."

        val stated = memories.count { it.provenance == MemoryProvenance.USER_STATED }
        val inferred = memories.size - stated
        summary.text = when {
            memories.isEmpty() -> "No memories stored."
            // Deliberately not "kept for N days". This store has no age sweep — that is
            // the memory persistence decision — so quoting a retention period would be a promise the
            // code does not make, in either direction.
            else -> "${memories.size} ${if (memories.size == 1) "memory" else "memories"} · " +
                "$stated stated by you · $inferred inferred · kept until you delete them"
        }
    }
}

/**
 * Rows for [MemoryActivity].
 *
 * The colour stripe is the provenance, which is the same job the stripe does in
 * [ActionLogAdapter] for success/failure: the distinction the screen exists to make is
 * readable down the edge of the list without reading any of it.
 */
class MemoryAdapter(
    private val onForget: (MemoryStore.Memory) -> Unit,
    private val onTogglePin: (MemoryStore.Memory) -> Unit
) : ListAdapter<MemoryStore.Memory, MemoryAdapter.MemoryViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory, parent, false)
        return MemoryViewHolder(view, onForget, onTogglePin)
    }

    override fun onBindViewHolder(holder: MemoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MemoryViewHolder(
        itemView: View,
        private val onForget: (MemoryStore.Memory) -> Unit,
        private val onTogglePin: (MemoryStore.Memory) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val bar: View = itemView.findViewById(R.id.provenanceBar)
        private val content: TextView = itemView.findViewById(R.id.content)
        private val meta: TextView = itemView.findViewById(R.id.meta)
        private val forget: Button = itemView.findViewById(R.id.forget)
        private val pin: Button = itemView.findViewById(R.id.pin)

        fun bind(m: MemoryStore.Memory) {
            bar.setBackgroundColor(
                when (m.provenance) {
                    MemoryProvenance.USER_STATED -> COLOR_STATED
                    MemoryProvenance.MODEL_INFERRED -> COLOR_INFERRED
                    MemoryProvenance.DAILY_SUMMARY -> COLOR_SUMMARY
                }
            )
            content.text = m.content

            val source = when (m.provenance) {
                MemoryProvenance.USER_STATED -> "you told the agent this"
                MemoryProvenance.MODEL_INFERRED -> "the agent inferred this"
                MemoryProvenance.DAILY_SUMMARY -> "daily summary${m.dayKey?.let { " · $it" } ?: ""}"
            }
            // The redaction note is not a footnote: a user checking whether their phone
            // number is on this device needs the answer for this row, not in general.
            val scrubbed = if (m.redacted) " · sensitive details replaced" else ""
            // Which half of memory injection this row is in, in the user's terms rather than ours. A
            // memory in the always-injected set shapes every reply and is billed on every
            // turn; one that isn't is consulted only when the agent goes looking. Those
            // are different things to be told about your own data, and the difference is
            // not recoverable from anything else on the row. Asked of [MemoryInjection]
            // rather than re-derived here, so a change to the tier rule cannot leave this
            // screen quietly describing the wrong one.
            val reach = when {
                m.pinned -> " · pinned, always in context"
                MemoryInjection.isAlwaysInjected(m) -> " · always in context"
                else -> " · used when relevant"
            }
            meta.text = "${formatTime(m.createdMs)} · $source$scrubbed$reach"

            forget.setOnClickListener { onForget(m) }

            // Enabled on every row, including ones already always-injected: pinning also
            // decides what survives when the injection budget binds, so it is never a
            // no-op.
            pin.text = if (m.pinned) "Unpin" else "Pin"
            pin.setTextColor(if (m.pinned) COLOR_PINNED else COLOR_UNPINNED)
            pin.setOnClickListener { onTogglePin(m) }
        }
    }

    companion object {
        private const val COLOR_STATED = 0xFF1E88E5.toInt()
        private const val COLOR_INFERRED = 0xFFBDBDBD.toInt()
        private const val COLOR_SUMMARY = 0xFF8E24AA.toInt()

        private const val COLOR_PINNED = 0xFF1E88E5.toInt()
        private const val COLOR_UNPINNED = 0xFF888888.toInt()

        // Main thread only, which is what makes sharing these safe.
        private val TIME_ONLY = SimpleDateFormat("HH:mm", Locale.US)
        private val WITH_DATE = SimpleDateFormat("MMM d, yyyy", Locale.US)

        /**
         * Memories have no age limit, so a row here can be arbitrarily old — which makes
         * the year part earn its place in a way it did not for the 90-day audit trail.
         * Same calendar-day rule as [ActionLogAdapter.formatTime]: "yesterday" must mean
         * yesterday, not 24 hours ago.
         */
        fun formatTime(timeMs: Long): String =
            (if (isToday(timeMs)) TIME_ONLY else WITH_DATE).format(Date(timeMs))

        private fun isToday(timeMs: Long): Boolean {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = timeMs }
            return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        }

        /** Rows carry a real database id, so identity needs none of the guesswork
         *  [ActionLogAdapter] does with its id-less domain type. */
        private val DIFF = object : DiffUtil.ItemCallback<MemoryStore.Memory>() {
            override fun areItemsTheSame(a: MemoryStore.Memory, b: MemoryStore.Memory) =
                a.id == b.id

            override fun areContentsTheSame(a: MemoryStore.Memory, b: MemoryStore.Memory) =
                a == b
        }
    }
}
