package edu.pitt.isl.mobileclaw.notiftest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Three dots that rise and fade in sequence while a turn is in flight.
 *
 * Animation distinguishes a pending request from model-generated text and provides
 * visible feedback while the app waits for the model service.
 *
 * Drawn rather than assembled from three animated child views: it is cheaper, and the
 * phase offset that makes the sequence read as a wave is trivial in one draw call and
 * fiddly across three animators.
 */
class ThinkingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val dotRadius = 3.5f * resources.displayMetrics.density
    private val dotGap = 6f * resources.displayMetrics.density
    private val rise = 2.5f * resources.displayMetrics.density

    init {
        paint.color = resolveOnSurfaceVariant()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (dotRadius * 6 + dotGap * 2).toInt()
        val h = (dotRadius * 2 + rise * 2).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val startX = dotRadius
        for (i in 0..2) {
            // A third of a cycle between neighbours, so the three read as one travelling
            // wave rather than three independent blinks.
            val t = phase - i * (PI2 / 3f)
            val lift = sin(t.toDouble()).toFloat()
            paint.alpha = (140 + 115 * ((lift + 1f) / 2f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                startX + i * (dotRadius * 2 + dotGap),
                cy - lift * rise,
                dotRadius,
                paint
            )
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun start() {
        if (animator != null) return
        // With animations disabled, hold the dots at rest rather than hiding the view:
        // the bubble still needs to occupy space, and a static row of dots still reads
        // as "waiting".
        if (!Motion.enabled(context)) { phase = 0f; invalidate(); return }
        animator = ValueAnimator.ofFloat(0f, PI2).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = null   // linear: a wave with eased phase stutters at the seam
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun resolveOnSurfaceVariant(): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
        )
        return if (tv.resourceId != 0)
            androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
        else tv.data
    }

    private companion object {
        const val PI2 = (2.0 * Math.PI).toFloat()
    }
}
