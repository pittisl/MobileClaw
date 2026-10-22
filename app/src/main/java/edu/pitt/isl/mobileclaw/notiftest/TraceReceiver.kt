package edu.pitt.isl.mobileclaw.notiftest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Controls the reasoning trace from adb while the phone screen is mirrored.
 *
 * ```
 * R=edu.pitt.isl.mobileclaw.notiftest/.TraceReceiver
 * adb shell am broadcast -n $R -a edu.pitt.isl.mobileclaw.TRACE_DUMP
 * adb shell am broadcast -n $R -a edu.pitt.isl.mobileclaw.TRACE_ON
 * adb shell am broadcast -n $R -a edu.pitt.isl.mobileclaw.TRACE_OFF
 * ```
 *
 * The explicit component (`-n`) is required because Android restricts implicit
 * broadcast delivery to manifest-declared receivers. The receiver is exported so
 * adb's shell uid can reach it, but refuses every operation outside debug builds.
 * All trace output is redacted by [ReasoningTrace]. Logcat is not readable across
 * ordinary apps; reading it through adb requires device debugging access.
 */
class TraceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The guard the manifest cannot express. Exported means anything on the device
        // can send these; a release build should do nothing with them.
        if (!BuildConfig.DEBUG) return

        // The receiver can be the first thing to touch settings in a cold process — the
        // broadcast starts the app if it is not running — and the getters return safe
        // defaults until init, which would silently read "trace off" and do nothing.
        AppSettings.init(context)

        when (intent.action) {
            ACTION_DUMP -> {
                Log.i(TAG, "trace dump requested over adb")
                ReasoningTrace.dump()
            }
            ACTION_ON -> setTrace(context, true)
            ACTION_OFF -> setTrace(context, false)
            else -> Log.w(TAG, "unknown trace action: ${intent.action}")
        }
    }

    /**
     * Writes through the same SharedPreferences the Settings switch uses, so the two
     * cannot disagree — flipping it from adb and then opening Settings shows the new
     * state rather than a stale toggle.
     *
     * `commit()` rather than `apply()`: this runs in a `BroadcastReceiver` whose process
     * may be torn down the moment `onReceive` returns, which is the same reasoning
     * [AgentRunState] documents for its own writer.
     */
    private fun setTrace(context: Context, on: Boolean) {
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putBoolean(AppSettings.KEY_REASONING_TRACE, on)
            .commit()
        Log.i(TAG, "reasoning trace ${if (on) "ENABLED" else "disabled"} over adb")
    }

    companion object {
        private const val TAG = "MobileClaw-TRACE"

        const val ACTION_DUMP = "edu.pitt.isl.mobileclaw.TRACE_DUMP"
        const val ACTION_ON = "edu.pitt.isl.mobileclaw.TRACE_ON"
        const val ACTION_OFF = "edu.pitt.isl.mobileclaw.TRACE_OFF"
    }
}
