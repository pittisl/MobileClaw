package edu.pitt.isl.mobileclaw.notiftest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Renders [ActionLogStore.ActionLogEntry] rows for [ActionLogActivity], newest first.
 *
 * The four fields carry different things depending on who recorded the row — a
 * notification button click, a tool, or the confirm gate itself — so the layout stays
 * generic rather than trying to special-case each source:
 *
 * | field | notification action | tool | confirm gate |
 * |---|---|---|---|
 * | `appName` | posting app | tool name | `"system"` / `"notification"` |
 * | `key` | notification key | target (number, title) | `"-"` |
 * | `actionTitle` | button title | command / argv | proposal description |
 *
 * Everything shown here was redacted by [ActionLogStore.record] before it was written,
 * so this displays what the export would contain — never more.
 */
class ActionLogAdapter : ListAdapter<ActionLogStore.ActionLogEntry, ActionLogAdapter.EntryViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_action_log, parent, false)
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusBar: View     = itemView.findViewById(R.id.statusBar)
        private val title: TextView     = itemView.findViewById(R.id.title)
        private val meta: TextView      = itemView.findViewById(R.id.meta)
        private val result: TextView    = itemView.findViewById(R.id.result)
        private val supplied: TextView  = itemView.findViewById(R.id.supplied)

        fun bind(e: ActionLogStore.ActionLogEntry) {
            statusBar.setBackgroundColor(themeColor(
                statusBar.context,
                if (e.success) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorError
            ))
            title.text = e.actionTitle

            // The key is worth showing when it names a target or an operation ("exec",
            // a phone number) and worth hiding when a caller had nothing to put there.
            meta.text = if (e.key.isBlank() || e.key == "-") {
                "${formatTime(e.timeMs)} · ${e.appName}"
            } else {
                "${formatTime(e.timeMs)} · ${e.appName} · ${e.key}"
            }

            result.text = e.result

            if (e.suppliedText.isNullOrEmpty()) {
                supplied.visibility = View.GONE
            } else {
                supplied.visibility = View.VISIBLE
                supplied.text = "text: \"${e.suppliedText}\""
            }
        }
    }

    companion object {
        /**
         * The stripe is drawn from the theme rather than from literals so it follows
         * the palette into dark mode. It was a fixed Material green and red, which
         * were the only two colours in the app that belonged to no scheme.
         */
        private fun themeColor(context: android.content.Context, attr: Int): Int {
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(attr, tv, true)
            return if (tv.resourceId != 0)
                androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
            else tv.data
        }

        // Main thread only, which is what makes sharing these safe — SimpleDateFormat
        // is not thread-safe.
        private val TIME_ONLY = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val WITH_DATE = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        /**
         * Retention keeps rows for 90 days, so a bare clock reading would silently
         * present last month's action as this afternoon's. Today stays short because
         * that is the overwhelmingly common case and the one being scanned quickly.
         */
        fun formatTime(timeMs: Long): String {
            val fmt = if (isToday(timeMs)) TIME_ONLY else WITH_DATE
            return fmt.format(Date(timeMs))
        }

        private fun isToday(timeMs: Long): Boolean {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = timeMs }
            return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        }

        /**
         * The domain type deliberately carries no row id — the Room entity is kept
         * separate so schema changes don't ripple into callers — so identity is the
         * fields a human would use to tell two rows apart. Two genuinely distinct
         * actions colliding on all three within the same millisecond would only cost a
         * redundant rebind, which is why this isn't worth widening the domain type for.
         */
        private val DIFF = object : DiffUtil.ItemCallback<ActionLogStore.ActionLogEntry>() {
            override fun areItemsTheSame(
                a: ActionLogStore.ActionLogEntry,
                b: ActionLogStore.ActionLogEntry
            ) = a.timeMs == b.timeMs && a.appName == b.appName && a.actionTitle == b.actionTitle

            override fun areContentsTheSame(
                a: ActionLogStore.ActionLogEntry,
                b: ActionLogStore.ActionLogEntry
            ) = a == b
        }
    }
}
