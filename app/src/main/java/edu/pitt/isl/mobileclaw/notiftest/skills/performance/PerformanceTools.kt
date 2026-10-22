package edu.pitt.isl.mobileclaw.notiftest.skills.performance

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.PendingAction
import edu.pitt.isl.mobileclaw.notiftest.ShellCommandPolicy
import edu.pitt.isl.mobileclaw.notiftest.ShellGate
import edu.pitt.isl.mobileclaw.notiftest.ShellRegistry
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject

/**
 * Agent tools that expose ProcessProbe to the chat agent.
 *
 * Both are privileged operations that degrade rather than fail: with a
 * backend connected they read `ps` and run `am force-stop` as uid 2000; without one
 * they fall back to the meminfo collector script and `killBackgroundProcesses`, which
 * are weaker but not nothing. Neither reports `capability_unavailable`, because both
 * still produce a partial answer — the capability signal belongs to `file_system`,
 * where there is no fallback at all.
 */

class MemoryMonitorTool(context: Context) : Tool {
    private val skill = ProcessProbe(context)

    override val name = "memory_monitor"
    override val description =
        "Report the top memory-consuming processes currently running on the device. Call this before kill_process to identify what to target."
    override val parameters = JSONObject(
        """{"type":"object","properties":{},"required":[]}"""
    )

    override suspend fun invoke(args: JSONObject): String {
        val snapshot = skill.topMemoryConsumers()
            ?: return "Memory data unavailable — reading other processes needs elevated access. " +
                "Either grant privileged shell access (Shizuku), or push and start " +
                "tools/meminfo_collector.sh via ADB."
        return skill.formatForAgent(snapshot)
    }
}

/**
 * Stops or restrains an app.
 *
 * Two interventions, and the distinction is the point. **Stopping** an app frees its
 * memory now and is undone the moment anything relaunches it — the next launch is cold,
 * so a stop that does not stick often costs more CPU and battery than it saved.
 * **Restricting** an app moves it into the `restricted` App Standby Bucket, which
 * persists across relaunch and curtails the background work that made it expensive in
 * the first place.
 *
 * The model is told which is which, because picking the wrong one is the difference
 * between fixing a problem and making it slightly worse every time the app comes back.
 * Restrict is the default recommendation for a persistent offender; stop is for
 * reclaiming memory right now.
 *
 * Both are privileged and confirm-gated. Without a backend, only the weak
 * `killBackgroundProcesses` path exists and restriction is unavailable, which the tool
 * says rather than silently doing something weaker than it claimed.
 */
class KillProcessTool(context: Context) : Tool {
    private val skill = ProcessProbe(context)

    override val name = "kill_process"
    override val description =
        "Stop or restrict an app by package name. action=\"stop\" frees its memory now, but the app " +
        "is free to relaunch and the next launch is slower — use it to reclaim memory immediately. " +
        "action=\"restrict\" puts the app in the restricted standby bucket, which persists across " +
        "relaunch and limits the background work that made it expensive — prefer this for an app " +
        "that keeps coming back. Use memory_monitor first to pick a target. The user always confirms."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "package_name": {
              "type": "string",
              "description": "Android package name to act on (e.g. com.google.android.gms). Use memory_monitor first to find candidates."
            },
            "action": {
              "type": "string",
              "enum": ["stop", "restrict"],
              "description": "\"stop\" ends the app now but does not persist; \"restrict\" limits its background work and does persist. Defaults to \"stop\"."
            }
          },
          "required": ["package_name"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val pkg = args.optString("package_name").trim()
        if (pkg.isEmpty()) return "I need a package name. Use memory_monitor first to see what's running."

        return when (args.optString("action").trim().lowercase().ifEmpty { "stop" }) {
            "restrict" -> restrict(pkg)
            "stop" -> if (ShellRegistry.isConnected) forceStop(pkg) else killBackground(pkg)
            else -> "Unknown action — use \"stop\" or \"restrict\"."
        }
    }

    /**
     * `am set-standby-bucket <pkg> restricted`, through the gate.
     *
     * Unlike stopping, this has no unprivileged fallback at all: there is no public API
     * for one app to change another's standby bucket, so without the bridge the honest
     * answer is that it cannot be done — not a weaker version of it.
     */
    private fun restrict(pkg: String): String {
        if (!ShellRegistry.isConnected) {
            return "Restricting an app needs privileged shell access (Shizuku), which isn't " +
                "connected. Nothing on the device can change another app's standby bucket " +
                "without it. I can stop the app instead, which frees memory now but is undone " +
                "when it next launches."
        }
        val argv = listOf("am", "set-standby-bucket", pkg, "restricted")
        // Vetted app-side as well as in the privileged process. Not redundant in the way
        // it looks: refusing here means no confirm dialog appears for a command that
        // would only be rejected at the far end after the user had already said yes.
        ShellCommandPolicy.reject(argv)?.let {
            return "Can't restrict \"$pkg\": $it."
        }
        return ShellGate.modify(
            source = SOURCE,
            operation = "set-standby-bucket",
            argv = argv,
            description = "Restrict $pkg (limits its background work, delays its jobs and " +
                "alarms; it can still be opened normally and this persists until changed)",
            reply = "Ready to restrict $pkg — waiting for your confirmation. This persists " +
                "across relaunch, unlike stopping the app."
        )
    }

    /**
     * The real stop, through the gate: `am force-stop` as uid 2000. Staged, never run —
     * [ShellGate.modify] has no parameter that could run it, and its proposal is exempt
     * from Full Automation Mode, so stopping an app is always something a human agreed
     * to.
     */
    private fun forceStop(pkg: String): String {
        val argv = listOf("am", "force-stop", pkg)
        // Vetted here as well as in the privileged process. Not redundant in the way it
        // looks: refusing app-side means no confirm dialog appears for a command that
        // would only be rejected at the far end after the user said yes.
        ShellCommandPolicy.reject(argv)?.let {
            return "Can't force-stop \"$pkg\": $it."
        }
        return ShellGate.modify(
            source = SOURCE,
            operation = "force-stop",
            argv = argv,
            description = "Force-stop $pkg (kills the app outright, including foreground " +
                "and background processes; unsaved state in it is lost)",
            reply = "Ready to force-stop $pkg — waiting for your confirmation. Note that " +
                "am force-stop reports success even for a package that was not running, " +
                "so a clean exit is not evidence anything was stopped."
        )
    }

    /** No bridge: the weak stop, reported as the request it is rather than as a kill. */
    private fun killBackground(pkg: String): String {
        PendingAction.propose(
            PendingAction.Proposal(
                description = "Kill background process: $pkg",
                execute = {
                    skill.killProcess(pkg)
                    "Requested a background-process kill for $pkg. Foreground, persistent, or " +
                        "system processes can't be killed this way, and Android may restart others. " +
                        "Granting privileged shell access would allow a real force-stop."
                }
            )
        )
        return "Ready to kill $pkg — waiting for your confirmation."
    }

    private companion object {
        /** Attribution in the audit trail, matching the tool name. */
        const val SOURCE = "kill_process"
    }
}
