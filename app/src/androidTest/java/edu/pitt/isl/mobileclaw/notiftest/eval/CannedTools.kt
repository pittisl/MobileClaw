package edu.pitt.isl.mobileclaw.notiftest.eval

import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject

data class ToolCallRecord(val name: String, val args: String, val result: String)

/**
 * Wraps a real tool so the model sees its real name, description and schema — the
 * catalog under test is the production catalog — while the call itself is recorded and,
 * for anything with a side effect or a permission behind it, answered from a canned
 * result instead of executed. The canned strings are written in the register the real
 * tools use ("prepared — waiting for confirmation"), because that text is what the model
 * reads next and a different register would change what it does.
 */
class RecordingTool(
    private val inner: Tool,
    private val canned: ((JSONObject) -> String)?,
    private val log: MutableList<ToolCallRecord>,
) : Tool {
    override val name get() = inner.name
    override val description get() = inner.description
    override val parameters get() = inner.parameters
    override suspend fun invoke(args: JSONObject): String {
        val result = canned?.invoke(args) ?: inner.invoke(args)
        log += ToolCallRecord(name, args.toString(), result)
        return result
    }
}

object CannedTools {

    /** Read-only tools with no permission behind them run for real against the eval state. */
    val PASS_THROUGH = setOf("read_notifications", "get_current_time", "recall")

    fun canned(name: String): ((JSONObject) -> String)? = when (name) {
        in PASS_THROUGH -> null

        "lookup_contact" -> { a ->
            val q = a.optString("query")
            if (q.contains("priya", true))
                "Priya Raman — phone: 412-555-0100 · email: priya.raman@example.com"
            else "No contact matches \"$q\"."
        }
        "send_sms" -> { a -> "SMS to ${a.optString("to")} prepared — waiting for the user's confirmation." }
        "compose_message" -> { _ -> "Message composer opened with the text pre-filled — the user taps send." }

        "dismiss_notification" -> { a ->
            "Dismissal of the notification matching \"${a.optString("query")}\" prepared — waiting for confirmation."
        }
        "reply_notification" -> { a ->
            "Reply to \"${a.optString("query")}\" prepared — waiting for confirmation."
        }
        "click_notification_action" -> { a ->
            "Pressing \"${a.optString("action")}\" on the notification matching \"${a.optString("query")}\" prepared — waiting for confirmation."
        }

        "read_calendar" -> { _ ->
            "Today:\n09:00 — Standup @ Zoom\n15:00 — Thesis meeting with Prof. Miller @ Benedum 1201\nTomorrow: nothing scheduled."
        }
        "create_event" -> { a -> "Event \"${a.optString("title")}\" prepared — waiting for confirmation." }

        "remember" -> { _ -> "Stored (user-stated)." }
        "forget" -> { _ -> "Forgotten." }

        "music_control" -> { a -> "Sent ${a.optString("action")} to the active media session (Spotify)." }
        "usage_context" -> { _ -> "Screen on 3 h 12 min today; top apps: Chrome 1 h 05, Slack 42 min, Spotify 38 min." }

        "memory_monitor" -> { _ ->
            "Memory by process (PSS, from the privileged bridge):\n" +
                "  1. com.example.hog — 1412 MB\n" +
                "  2. com.android.chrome — 612 MB\n" +
                "  3. edu.pitt.isl.mobileclaw.notiftest — 188 MB\n" +
                "  4. com.spotify.music — 143 MB"
        }
        "kill_process" -> { a -> "am force-stop ${a.optString("package_name")} prepared — waiting for confirmation." }

        "file_system" -> { a ->
            when (a.optString("operation")) {
                "ls" -> "Documents/\n  hotel-austin-receipt.txt  1.2 KB\n  thesis-notes.md  18 KB\n  taxes-2025.pdf  240 KB"
                "cat" -> "Marriott Austin Downtown\nCheck-in 03/12, check-out 03/14\nRoom 2 nights ......... \$164.00\nTaxes ................. \$20.20\nTotal ................. \$184.20"
                "stat", "find" -> "/sdcard/Documents/hotel-austin-receipt.txt  1.2 KB  modified 03/14"
                else -> "${a.optString("operation")} on ${a.optString("path")} prepared — waiting for confirmation."
            }
        }
        "search_files" -> { _ ->
            "1. /sdcard/Documents/hotel-austin-receipt.txt\n   …Marriott Austin Downtown … Total ................. \$184.20…"
        }

        "notion_search" -> { a ->
            "Pages matching \"${a.optString("query")}\":\n- Groceries (id 1a2b3c4d-0000-0000-0000-000000000001)"
        }
        "notion_get_page" -> { _ -> "Page: Groceries\n[blk-1] to_do: eggs\n[blk-2] to_do: bread" }
        "notion_edit_page" -> { a -> "${a.optString("operation")} on the Notion page prepared — waiting for confirmation." }

        "call_app_function" -> { a ->
            val f = a.optString("function_id")
            when {
                f.endsWith("searchContacts") -> "Result: [{\"name\":\"Alice\",\"endpointValue\":\"alice@chatapp\"}]"
                f.endsWith("sendMessage") -> "AppFunction call prepared — waiting for confirmation."
                else -> "Result: []"
            }
        }

        else -> { _ -> "[eval: no canned result for $name]" }
    }
}
