package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import edu.pitt.isl.mobileclaw.notiftest.skills.search.NewMediaWorker
import edu.pitt.isl.mobileclaw.notiftest.skills.search.SearchIndexWorker

/**
 * Process entry point. Primes shared state that must be ready before either the
 * Activity or the background listener touches it:
 *  - [LlmConfig] (loads encrypted runtime model profiles before any client is built),
 *  - [AppSettings] (so the confirm gate can read Full Automation Mode),
 *  - [AgentRunState] (so a pause survives process death — read before [NotifListener]
 *    can be handed the first notification, since the whole point of persisting it is
 *    that a restart must not silently resume the agent),
 *  - [ActionLogStore] (opens the Room database; primed before the listener or any
 *    tool can record an action, so nothing has to be buffered in practice),
 *  - [ChatStore] (restores the stored conversation before the UI binds to it),
 *  - [DecisionStore] (restores the triage log, which [ReasoningModule] feeds back into
 *    its prompt — primed before the listener can produce the first decision),
 *  - [MemoryStore] (opens the memory table; primed before the first chat turn can call
 *    the memory tools, since unlike the audit trail it does not buffer pre-init writes),
 *  - [SkillRegistry] (builds each skill's context-dependent tools),
 *  - [AppForeground] tracking (so [PendingAction] knows whether to confirm in an
 *    Activity dialog or an over-other-apps overlay),
 *  - [OverlayConfirm] (holds the app context used to draw that overlay),
 *  - [RunStateNotification] (the agent's status in the shade, and the pause button that
 *    is the only control available while the app is closed),
 *  - [ShizukuBridge] (registers the listeners that install a privileged backend if
 *    one is available; a no-op on the overwhelmingly common device with no Shizuku),
 *  - [DailyPassWorker] (enqueues the once-a-day memory consolidation — here rather
 *    than in an Activity because it has to keep running for a user who leaves the app
 *    closed for a week; unique work with KEEP, so a process restart doesn't reset it),
 *  - [SearchIndexWorker] and [NewMediaWorker] (the file index's twelve-hour rebuild, and
 *    the MediaStore trigger that adds a new photo or download within seconds).
 *
 * Registered via `android:name` in the manifest.
 *
 * Note this runs in the **app** process only. The privileged user service
 * ([PrivilegedService]) is a separate process that Shizuku starts, and none of this
 * happens there — which is why that class must not reach for any of these singletons.
 */
class MobileClawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LlmConfig.init(this)
        AppSettings.init(this)
        // Before any Activity inflates, so a cold start opens in the chosen scheme.
        AppSettings.applyTheme()
        AgentRunState.init(this)
        ActionLogStore.init(this)
        ChatStore.init(this)
        DecisionStore.init(this)
        MemoryStore.init(this)
        SkillRegistry.init(this)
        OverlayConfirm.init(this)
        NotifConfirm.init(this)
        RunStateNotification.init(this)
        registerActivityLifecycleCallbacks(AppForeground)
        ShizukuBridge.init()
        DailyPassWorker.schedule(this)
        SearchIndexWorker.schedule(this)
        NewMediaWorker.schedule(this)
    }
}
