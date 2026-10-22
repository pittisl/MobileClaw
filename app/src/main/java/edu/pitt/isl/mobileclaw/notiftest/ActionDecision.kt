package edu.pitt.isl.mobileclaw.notiftest

/**
 * Structured output produced by ReasoningModule for a single notification.
 */
data class ActionDecision(
    val notificationKey: String,
    val action: Action,
    val priority: Priority,
    val reasoning: String,
    val replyText: String,           // non-empty only when action == REPLY
    val confidence: Float,           // 0.0–1.0; how certain the model is
    val snoozeDurationMinutes: Int?, // non-null only when action == SNOOZE
    val actionTitle: String = ""     // non-empty only when action == CLICK_ACTION
) {
    enum class Action {
        REPLY, DISMISS, SUMMARIZE, ESCALATE, SNOOZE, CLICK_ACTION, DO_NOTHING;

        companion object {
            fun fromString(s: String): Action =
                entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: DO_NOTHING
        }
    }

    enum class Priority {
        HIGH, MEDIUM, LOW;

        companion object {
            fun fromString(s: String): Priority =
                entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: LOW
        }
    }

    companion object {
        fun fallback(notificationKey: String, reason: String) = ActionDecision(
            notificationKey = notificationKey,
            action = Action.DO_NOTHING,
            priority = Priority.LOW,
            reasoning = "Reasoning failed: $reason",
            replyText = "",
            confidence = 0.0f,
            snoozeDurationMinutes = null
        )
    }
}
