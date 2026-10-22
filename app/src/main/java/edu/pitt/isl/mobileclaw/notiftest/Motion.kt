package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.provider.Settings
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * The motion vocabulary.
 *
 * Durations and curves live here rather than at each call site so the interface moves
 * with one voice — mismatched easing is as legible as mismatched spacing, and just as
 * hard to name when you see it.
 *
 * The scheme is deliberately restrained. Only three things animate, each because it
 * reports something the user cannot otherwise see:
 *
 *  - **The run-state dot breathes** while the agent is running, and holds still when
 *    paused. An autonomous process is invisible by nature; this is the only continuous
 *    evidence on screen that it is alive.
 *  - **Timeline entries rise as they land**, which distinguishes something that just
 *    arrived from something that was already there — the distinction the whole screen
 *    exists to convey.
 *  - **The thinking indicator**, because a request in flight with no feedback is
 *    indistinguishable from a broken one.
 *
 * Nothing else moves. Decorative motion is what makes an interface feel generated.
 */
object Motion {

    /** Fast enough to feel immediate; the floor below which motion reads as a glitch. */
    const val QUICK = 140L

    /** The default for anything entering or leaving. */
    const val ENTER = 220L

    /** One breath of the run-state dot, out and back. */
    const val BREATH = 2200L

    /**
     * Standard easing — decelerating, matching Material's emphasis curve. Things enter
     * quickly and settle, which is how physical objects arrive.
     */
    val DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** Symmetric curve for anything that loops, so the reversal has no visible seam. */
    val SMOOTH: Interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)

    /**
     * Whether animation should run at all.
     *
     * Honours the system animation scale, which is what "Remove animations" in
     * accessibility settings and battery saver both drive. Checked at each call rather
     * than cached: a user who turns animations off mid-session means it, and this is
     * also the switch that makes a demo device deterministic when recording video.
     */
    fun enabled(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
}
