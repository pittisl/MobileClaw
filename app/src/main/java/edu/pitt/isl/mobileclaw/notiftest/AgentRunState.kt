package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the background agent is working: the single flag that decides if a posted
 * notification is captured and reasoned about at all.
 *
 * The user can pause background work without revoking notification access or
 * force-stopping the app, then resume it deliberately.
 *
 * ### What paused covers
 *
 * Paused stops **capture as well as reasoning** — [NotifListener] returns before it
 * touches [NotifStore]. "Paused" is a promise, and its honest reading is *nothing is
 * being recorded*, not *nothing is being sent*: a user who pauses because of what is on
 * screen is not served by a version that still files it away for the next chat turn to
 * hand to the model through `buildNotifContext`.
 *
 * The **foreground chat is deliberately unaffected**. It is user-initiated by
 * construction — you pressed Send — and a pause that breaks the chat panel also hides
 * the control that undoes it.
 *
 * ### Why this is persisted
 *
 * A pause that silently lapses on the next process death is worse than no pause at all,
 * because the user believes they turned it off and stops watching. `SharedPreferences`
 * rather than Room for the same reason as [AppSettings]: it is one boolean that has to
 * be readable synchronously by a broadcast receiver in a cold process.
 *
 * The default is **running**; a user-selected pause persists across process death.
 */
object AgentRunState {

    private const val TAG = "MobileClaw-RS"

    /** Not in `preferences.xml`: this is driven by the main screen and the sticky
     *  notification, not by a Settings switch buried two taps away from the thing it
     *  turns off. */
    const val KEY_PAUSED = "agent_paused"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _paused = MutableStateFlow(false)

    /** True while the background loop is stopped. Observed by the UI and the sticky
     *  notification; read directly by [NotifListener] on every notification. */
    val paused: StateFlow<Boolean> = _paused

    val isPaused: Boolean get() = _paused.value

    /**
     * Reads the stored state into the flow. Safe to call repeatedly — [MobileClawApp]
     * calls it, and so does [NotifListener], because the listener can be bound by the
     * system in a process where the Application object has run but the user has never
     * opened the Activity.
     */
    fun init(context: Context) {
        val p = prefs ?: PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .also { prefs = it }
        _paused.value = p.getBoolean(KEY_PAUSED, false)
    }

    /**
     * Sets the run state and returns true if it actually changed, so callers can skip
     * re-posting the notification for a no-op tap.
     *
     * Writes with `commit()` rather than `apply()`: this is called from a broadcast
     * receiver, whose process can be killed the moment [android.content.BroadcastReceiver.onReceive]
     * returns. An asynchronous write is exactly the case where a pause would appear to
     * take effect and then not survive.
     */
    fun setPaused(value: Boolean): Boolean {
        if (_paused.value == value) return false
        prefs?.edit()?.putBoolean(KEY_PAUSED, value)?.commit()
            ?: Log.w(TAG, "setPaused($value) before init — state is in memory only")
        _paused.value = value
        Log.i(TAG, if (value) "Agent paused" else "Agent resumed")
        return true
    }
}
