package edu.pitt.isl.mobileclaw.notiftest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Draws the confirm-gate proposal as a system overlay (`TYPE_APPLICATION_OVERLAY`)
 * so a background triage action can be approved while the user is in another app,
 * instead of being silently dropped when no Activity is foregrounded.
 *
 * Requires the "Display over other apps" (SYSTEM_ALERT_WINDOW) special access.
 * [tryShow] returns false when the controller isn't initialised or the permission
 * isn't granted, so [PendingAction] can fall back to the fail-safe drop.
 */
object OverlayConfirm {

    private const val TAG = "MobileClaw-OV"

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Only touched on the main thread, where WindowManager add/remove must run.
    private val visible = mutableMapOf<() -> Unit, PendingAction.Proposal>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Renders [proposal] as an overlay with Approve / Reject buttons. Returns true
     * if the overlay was shown; false if overlays aren't available (caller should
     * fall back). Approving runs [PendingAction.Proposal.execute] off the UI thread
     * and echoes the result to the chat panel.
     */
    fun tryShow(proposal: PendingAction.Proposal): Boolean {
        if (!PendingAction.isCurrent(proposal)) return true
        val ctx = appContext ?: return false
        if (!Settings.canDrawOverlays(ctx)) return false
        main.post { showView(ctx, proposal) }
        return true
    }

    @SuppressLint("InflateParams")
    private fun showView(ctx: Context, proposal: PendingAction.Proposal) {
        if (!PendingAction.isCurrent(proposal)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // The overlay is the confirm gate seen from outside the app, so it wears the same
        // inversion the in-app dialog does — a card the opposite value of the interface
        // it interrupts. It reads from the palette rather than the fixed #1E1E1E it used
        // to carry, so the two halves of one feature cannot drift apart.
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val surface = ContextCompat.getColor(ctx, R.color.mc_inverse_surface)
        val onSurface = ContextCompat.getColor(ctx, R.color.mc_inverse_ink)
        val onSurfaceMuted = ContextCompat.getColor(ctx, R.color.mc_inverse_ink_muted)
        val plexSans = ResourcesCompat.getFont(ctx, R.font.plex_sans)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(18f), px(20f), px(12f))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = px(16f).toFloat()
            }
            elevation = px(8f).toFloat()
        }

        card.addView(TextView(ctx).apply {
            text = "Confirm action"
            setTextColor(onSurface)
            textSize = 17f
            typeface = plexSans
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(6f))
        })
        card.addView(TextView(ctx).apply {
            text = proposal.description
            setTextColor(onSurfaceMuted)
            textSize = 15f
            typeface = plexSans
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, px(14f))
        })

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable so the buttons receive taps; NOT_TOUCH_MODAL lets touches
            // outside the card pass through to whatever app is underneath.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 96
            // Leave side margins so it reads as a floating card, not a full bar.
            width = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()
        }

        // Guard against a stuck overlay: remove exactly once, whichever button fires.
        var removed = false
        lateinit var withdraw: () -> Unit
        fun remove() {
            if (removed) return
            removed = true
            visible.remove(withdraw)
            try { wm.removeView(card) } catch (e: Exception) { Log.w(TAG, "removeView failed", e) }
        }
        withdraw = ::remove

        // Same asymmetry as the in-app dialog: approving is the emphatic control,
        // declining is quiet. Taking an action on the user's behalf should require
        // finding the filled button; declining should not.
        buttonRow.addView(TextView(ctx).apply {
            text = "Not now"
            setTextColor(onSurfaceMuted)
            textSize = 14f
            typeface = plexSans
            isClickable = true
            setPadding(px(14f), px(10f), px(14f), px(10f))
            setOnClickListener {
                remove()
                PendingAction.decline(proposal)
                ActionLogStore.record(
                    appName = "overlay", key = "-", actionTitle = proposal.description,
                    suppliedText = null, result = "Rejected by user (overlay).", success = false
                )
            }
        })
        buttonRow.addView(TextView(ctx).apply {
            text = "Do it"
            setTextColor(surface)
            textSize = 14f
            typeface = plexSans
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            setPadding(px(18f), px(10f), px(18f), px(10f))
            background = GradientDrawable().apply {
                setColor(onSurface)
                cornerRadius = px(8f).toFloat()
            }
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = px(8f)
            setOnClickListener {
                remove()
                scope.launch {
                    PendingAction.executeAndPublish(proposal)
                }
            }
        })

        card.addView(buttonRow)

        try {
            wm.addView(card, layoutParams)
            visible[withdraw] = proposal
        } catch (e: Exception) {
            // e.g. permission revoked between the canDrawOverlays check and addView.
            Log.w(TAG, "Failed to add overlay; dropping proposal", e)
            ActionLogStore.record(
                appName = "overlay", key = "-", actionTitle = proposal.description,
                suppliedText = null, result = "Overlay unavailable — action dropped.", success = false
            )
        }
    }

    internal fun resetForDemo() {
        // Pending showView callbacks check the generation again, so they cannot put
        // an old overlay back on screen after this withdrawal runs.
        val withdrawAll = {
            visible.filterValues { !PendingAction.isCurrent(it) }.keys.toList().forEach { it() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) withdrawAll()
        else main.post { withdrawAll() }
    }
}
