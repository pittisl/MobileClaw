package edu.pitt.isl.mobileclaw.notiftest

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Timeline arrivals rise into place instead of appearing.
 *
 * The distinction this draws is the one the screen exists to convey: something that just
 * happened versus something that was already there. In a live demo the audience is
 * watching for cause and effect — a notification is fired, and a moment later an entry
 * lands on the rail — and an instant cut makes that connection easy to miss.
 *
 * Only additions are animated. Streaming replies rewrite one bubble roughly twenty times
 * a second (`MainViewModel.STREAM_FLUSH_MS`), so animating *changes* would leave the
 * assistant's turn permanently mid-transition. `DefaultItemAnimator`'s change animation
 * is disabled for the same reason.
 */
class TimelineItemAnimator : DefaultItemAnimator() {

    init {
        addDuration = Motion.ENTER
        removeDuration = Motion.QUICK
        moveDuration = Motion.QUICK
        // A cross-fade between old and new content is exactly wrong for a bubble whose
        // text is being rewritten as tokens arrive: it would ghost every frame.
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        if (!Motion.enabled(view.context)) return super.animateAdd(holder)

        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 8f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.ENTER)
            .setInterpolator(Motion.DECELERATE)
            .withEndAction {
                view.alpha = 1f
                view.translationY = 0f
                dispatchAddFinished(holder)
            }
            .start()
        return false   // we are driving this one; RecyclerView should not also run it
    }
}
