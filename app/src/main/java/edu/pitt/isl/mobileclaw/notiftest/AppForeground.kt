package edu.pitt.isl.mobileclaw.notiftest

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether any of the app's Activities is currently in the foreground, by
 * counting started Activities via [Application.ActivityLifecycleCallbacks].
 *
 * The confirm gate uses this to decide *where* a proposal is confirmed: an
 * in-Activity dialog when foregrounded, an over-other-apps overlay when not. Register once from [MobileClawApp.onCreate].
 */
object AppForeground : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)

    /** True between some Activity's onStart and its onStop. */
    val isForeground: Boolean
        get() = startedActivities.get() > 0

    override fun onActivityStarted(activity: Activity) { startedActivities.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) {
        startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
