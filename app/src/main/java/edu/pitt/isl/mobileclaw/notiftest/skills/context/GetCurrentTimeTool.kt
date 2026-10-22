package edu.pitt.isl.mobileclaw.notiftest.skills.context

import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Returns the current local date/time. Trivial, but reliably exercises the tool loop. */
class GetCurrentTimeTool : Tool {
    override val name = "get_current_time"
    override val description = "Get the current local date and time on the device."
    override val parameters = JSONObject("""{ "type": "object", "properties": {} }""")
    override suspend fun invoke(args: JSONObject): String =
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US).format(Date())
}
