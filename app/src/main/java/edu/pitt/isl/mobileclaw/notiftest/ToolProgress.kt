package edu.pitt.isl.mobileclaw.notiftest

/**
 * Display-only progress for a turn that has not started answering yet. These labels
 * describe tool events, never their arguments, results, or whether a lookup succeeded.
 */
internal class ToolProgress {
    private data class Labels(val running: String, val finished: String)
    private data class Entry(val labels: Labels, val finished: Boolean = false)

    private val entries = linkedMapOf<String, Entry>()

    fun update(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.ToolCall -> {
            val labels = labelsFor(event.name)
            if (labels == null) false else {
                entries[event.id] = Entry(labels)
                while (entries.size > 3) entries.remove(entries.keys.first())
                true
            }
        }
        is AgentEvent.ToolResult -> {
            val entry = entries[event.id]
            if (entry == null) false else {
                entries[event.id] = entry.copy(finished = true)
                true
            }
        }
        else -> false
    }

    val text: String
        get() = entries.values.joinToString("\n") {
            if (it.finished) it.labels.finished else it.labels.running
        }

    private fun labelsFor(name: String): Labels? = when (name) {
        "lookup_contact" -> Labels("Looking up contact…", "Contact lookup finished")
        "send_photo" -> Labels("Looking up contact and preparing photo…", "Photo preparation finished")
        "read_calendar" -> Labels("Checking calendar…", "Calendar lookup finished")
        "search_files" -> Labels("Searching files…", "File search finished")
        "read_notifications" -> Labels("Reading notifications…", "Notification lookup finished")
        "get_current_time" -> Labels("Checking current time…", "Time lookup finished")
        "usage_context" -> Labels("Checking phone activity…", "Phone activity lookup finished")
        "recall" -> Labels("Checking saved memory…", "Memory lookup finished")
        else -> null
    }
}
