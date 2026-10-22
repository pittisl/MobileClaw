package edu.pitt.isl.mobileclaw.notiftest.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.service.AppFunction

/**
 * The app's `ping` AppFunction answers `pong` to exercise the platform bus end to
 * end: metadata indexing, `AppFunctionManager` marshalling and service dispatch.
 * An app may call its own function without privileged cross-app execution access.
 *
 * The function is disabled by default and has no side effects. [AppFunctionPing]
 * enables it for the self-call demonstration; this is separate from calling another
 * app through the confirmed privileged bridge.
 *
 * KSP (`appfunctions-compiler`) generates the metadata and service inventory from
 * this class, which therefore needs a public no-argument constructor.
 */
class MobileClawFunctions {

    /**
     * Echo check for the AppFunctions bus.
     *
     * @param message text to echo back
     * @return "pong: " followed by the message
     */
    @AppFunction(isEnabled = false)
    fun ping(appFunctionContext: AppFunctionContext, message: String): String = "pong: $message"
}
