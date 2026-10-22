package edu.pitt.isl.mobileclaw.notiftest.skills.context

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reports recent foreground app usage so the agent can reason with
 * context ("user's been in Slack 20 min → probably still working"). Read-only and
 * ungated; requires the PACKAGE_USAGE_STATS special access, which is granted from
 * a dedicated Settings page (not a runtime dialog).
 */
class UsageContextTool(private val context: Context) : Tool {

    override val name = "usage_context"
    override val description =
        "Report which apps the user has used recently and for how long, over a time window (default 60 minutes)."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "window_minutes": { "type": "integer", "description": "How far back to look, in minutes (default 60, max 1440)." }
          }
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String = withContext(Dispatchers.IO) {
        if (!Capability.USAGE_ACCESS.isGranted(context)) {
            PermissionSignal.request(Capability.USAGE_ACCESS)
            return@withContext Capability.unavailable(Capability.USAGE_ACCESS)
        }

        val windowMin = args.optInt("window_minutes", 60).coerceIn(1, 1440)
        val end = System.currentTimeMillis()
        val begin = end - windowMin * 60_000L

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            ?.filter { it.totalTimeInForeground > 0 }
            ?.sortedByDescending { it.totalTimeInForeground }
            ?.take(8)
            .orEmpty()

        if (stats.isEmpty()) {
            return@withContext "No app usage recorded in the last $windowMin minutes."
        }

        val pm = context.packageManager
        "App usage (last $windowMin min):\n" + stats.joinToString("\n") { s ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                s.packageName
            }
            val minutes = s.totalTimeInForeground / 60_000
            val seconds = (s.totalTimeInForeground / 1000) % 60
            "  $label — ${if (minutes > 0) "${minutes}m " else ""}${seconds}s"
        }
    }
}
