package edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Calling other apps' AppFunctions — Android 16's own typed function bus — through the
 * privileged bridge.
 *
 * Why the bridge and not the API: the caller side of AppFunctions needs
 * `EXECUTE_APP_FUNCTIONS`, which is `internal|privileged` and unreachable for an app.
 *  The `shell` user holds it, and this Android 16 build gives it a shell command
 * (`cmd app_function execute-app-function`), so the uid-2000 process MobileClaw already
 * runs for files and processes is the one place the call can be made. The skill declares
 * one tool; the vector it builds is admitted by `ShellCommandPolicy` on both sides of the
 * bridge and staged by `ShellGate.modify`, so a call executes only after the user
 * confirms it — an AppFunction is arbitrary code with side effects in another app, and
 * this is exactly the case the confirm gate exists for.
 *
 * Discovery is not offered: this build's `cmd app_function` cannot list, and the Jetpack
 * reader returns nothing for packages we lack permission on. The tool's description names
 * the functions it knows.
 */
class AppFunctionsSkill : Skill {
    override val id = "appfunctions"
    override val displayName = "App functions"
    override val summary =
        "Call functions other apps expose on Android's AppFunctions bus (Android 16), " +
            "after you confirm. Needs privileged shell access."
    override val capabilities = listOf(Capability.PRIVILEGED_SHELL)

    override fun tools(context: Context): List<Tool> = listOf(CallAppFunctionTool())
}
