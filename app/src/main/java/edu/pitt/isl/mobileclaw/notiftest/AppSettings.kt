package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Thin accessor over the default [SharedPreferences] backing the Settings screen
 * (`res/xml/preferences.xml`, surfaced by `SettingsActivity`). Lets non-UI code —
 * the confirm gate, the listener service — read user preferences without each
 * holding its own `Context`.
 *
 * [init] must run once with any context before the getters are read; it's called
 * from both the ViewModel (foreground) and the listener service (background), so
 * either entry point primes it. Until then the getters return safe defaults
 * (automation OFF), so a missing init never silently enables a dangerous mode.
 */
object AppSettings {

    /** Key shared with the SwitchPreference in `res/xml/preferences.xml`. */
    const val KEY_FULL_AUTOMATION = "full_automation_mode"

    /** Key shared with the reasoning-trace SwitchPreference. See [reasoningTrace]. */
    const val KEY_REASONING_TRACE = "reasoning_trace"

    /** Key shared with the appearance ListPreference. See [nightMode]. */
    const val KEY_THEME = "theme_mode"

    /** Key of the Settings › File search switch. See [labelPhotos]. */
    const val KEY_LABEL_PHOTOS = "label_photos"

    /** Key of the Settings › Automation follow-up switch. See [followUp]. */
    const val KEY_FOLLOW_UP = "notification_follow_up"

    /** Shared read/write selection. Absent/all reads every calendar but never guesses a write destination. */
    const val KEY_CALENDAR_SOURCE = "calendar_read_source"
    val calendarReadSource: String get() = prefs?.getString(KEY_CALENDAR_SOURCE, "all") ?: "all"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    /**
     * When true, action proposals execute immediately without asking the user to
     * confirm. Dangerous: the agent can dismiss/reply/press buttons unattended.
     * Defaults to false (and stays false if [init] hasn't run yet).
     */
    val fullAutomation: Boolean
        get() = prefs?.getBoolean(KEY_FULL_AUTOMATION, false) ?: false

    /**
     * Enables live [ReasoningTrace] output, including redacted prompts and execution
     * events. It is off by default because full prompts can fill logcat's ring buffer.
     * Replaying stored records through [ReasoningTrace.dump] is independent of this flag.
     */
    val reasoningTrace: Boolean
        get() = prefs?.getBoolean(KEY_REASONING_TRACE, false) ?: false

    /**
     * When true, the file index runs the on-device models over photos — the classifier and the CLIP embedder — so `search_files` can find them by what they
     * show, and the text recogniser over photos and PDFs. One switch for all
     * three: they are the same kind of cost and the same kind of claim about a file's
     * pixels, and "don't run models over my files" is one wish, not three. On by default — the
     * feature is the point — but a switch because it is the one part of indexing that
     * costs more than a read, and a dependency that sends usage telemetry (ML Kit's usage
     * telemetry). Off means photos and PDFs are searchable by name only, and what is already in
     * the index are dropped at the next rebuild rather than kept: an "off" that still
     * answered "cat" from last week's labels would be a switch that does nothing visible.
     */
    val labelPhotos: Boolean
        get() = prefs?.getBoolean(KEY_LABEL_PHOTOS, true) ?: true

    /**
     * When true, a triaged notification that [FollowUp.shouldRun] admits gets a second,
     * tool-using turn that reads the calendar, recent usage and memories and posts a
     * suggestion to the chat. On by default — it is the feature — but a
     * switch because it is the one thing that multiplies LLM calls per notification, and
     * unlike [fullAutomation] its worst case is a bill, not an action: everything it can
     * call only reads. Before [init], off: a service that has not primed settings should
     * not spend.
     */
    val followUp: Boolean
        get() = prefs?.getBoolean(KEY_FOLLOW_UP, true) ?: false

    /**
     * Light / dark / follow-system, as an [AppCompatDelegate] night-mode constant.
     *
     * Following the system is the right default, but it is the wrong *only* option here:
     * the paper figures want the light scheme (it reproduces better on white) and the
     * conference monitor wants the dark one, and neither should require digging through
     * the phone's own display settings mid-demo to get.
     */
    val nightMode: Int
        get() = when (prefs?.getString(KEY_THEME, THEME_SYSTEM)) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    /**
     * Applies the stored choice. Called from [MobileClawApp.onCreate] so a cold start
     * honours it — without that the app would open in the system's scheme and only switch
     * once Settings had been visited, which looks like a bug rather than a preference.
     */
    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
}
