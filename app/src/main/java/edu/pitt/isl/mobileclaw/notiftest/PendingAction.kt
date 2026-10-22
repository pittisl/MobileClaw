package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Confirm-before-acting gate. Write tools (reply, dismiss, …) don't execute
 * directly — they stage a [Proposal] here. Only on the user's OK does
 * [Proposal.execute] run. This keeps irreversible/outward actions (sending a
 * message!) under explicit user control.
 *
 * A proposal is confirmed wherever the user can actually see it:
 *  - **Foreground** → the Activity shows a native confirm dialog (it collects
 *    [proposals]).
 *  - **Background** → [OverlayConfirm] draws an over-other-apps overlay, so a
 *    background triage action isn't silently dropped just because no Activity is
 *    on screen. Requires the overlay permission.
 *  - **Background with no overlay permission** → [NotifConfirm] posts a heads-up
 *    notification with Approve / Reject. This needs no special access, so it is the
 *    path most installs actually use.
 *  - **Nothing left** (notifications disabled too) → fail-safe: the proposal is
 *    dropped, but the drop is recorded in [ActionLogStore] rather than vanishing.
 *
 * Exception: when [AppSettings.fullAutomation] is enabled, proposals execute
 * immediately without any confirmation (and regardless of whether the UI is in
 * the foreground). That's the deliberately dangerous "Full Automation Mode" — with
 * one carve-out, [Proposal.allowFullAutomation], for actions that must never run
 * unattended no matter what the user switched on.
 */
object PendingAction {

    data class Proposal(
        val description: String,
        /**
         * When false, [AppSettings.fullAutomation] does **not** apply and the user is
         * asked every time. Set it for actions whose worst case is unrecoverable —
         * `file_system` writes and deletes, and anything else that will run through
         * the privileged shell bridge. Full Automation is a convenience
         * for dismissing notifications; it should not also be a standing licence for
         * an unattended `rm -rf` reached by prompt injection.
         */
        val allowFullAutomation: Boolean = true,
        /** Photo confirmations belong to one visible conversation and show local outgoing pixels. */
        val sessionId: String? = null,
        val imagePreviewPath: String? = null,
        val onDiscard: (() -> Unit)? = null,
        val execute: suspend () -> String
    ) {
        // Capture creation rather than display: an old coroutine may reach the gate
        // after reset, and a buffered dialog proposal may reach the UI even later.
        internal val generation = demoGeneration
    }

    @Volatile
    private var demoGeneration = 0L
    @Volatile
    private var demoResetInProgress = false

    private val _proposals = MutableSharedFlow<Proposal>(extraBufferCapacity = 1)
    val proposals: SharedFlow<Proposal> = _proposals

    // Runs auto-executed proposals off the UI; survives any single failure.
    private val autoScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Stage an action for the user to confirm — unless Full Automation Mode is on,
     * in which case it runs right away and the result is echoed to the chat panel.
     */
    @Synchronized
    fun propose(proposal: Proposal): Boolean {
        if (!isCurrent(proposal)) return false
        // A notification or overlay cannot show the image/recipient review required
        // for sharing a selected photo. Keep this route in the originating chat.
        if (proposal.sessionId != null && !AppForeground.isForeground) return false
        if (AppSettings.fullAutomation && proposal.allowFullAutomation) {
            autoScope.launch {
                executeAndPublish(proposal, auto = true)
            }
            return true
        }

        // Ordered by how directly the user is already looking at the thing being
        // confirmed, not by how easy it is to show.
        val delivered = when {
            // Foreground: the Activity's dialog handles it (it collects [proposals]).
            AppForeground.isForeground -> _proposals.subscriptionCount.value > 0 && _proposals.tryEmit(proposal)
            // Background: confirm via an over-other-apps overlay if we can.
            OverlayConfirm.tryShow(proposal) -> true
            // Background, no overlay permission: a heads-up notification with Approve /
            // Reject, which needs no special access and is therefore the path most
            // installs will actually take.
            NotifConfirm.tryShow(proposal) -> true
            // Nothing can reach the user — notifications are off too. Fail-safe drop,
            // recorded rather than silent.
            else -> false
        }
        if (!delivered) ActionLogStore.record(
                appName = "system", key = "-", actionTitle = proposal.description,
                suppliedText = null,
                result = "Dropped — no active confirmation surface could accept this action.",
                success = false
            )
        return delivered
    }

    fun isCurrent(proposal: Proposal): Boolean =
        !demoResetInProgress && proposal.generation == demoGeneration &&
            (proposal.sessionId == null || proposal.sessionId == ChatStore.activeSessionId.value)

    /**
     * Admission happens immediately before the action starts, including actions whose
     * Approve callback or auto-execution coroutine was queued before reset. An action
     * already admitted may have sent something; resetting cannot undo external effects.
     */
    suspend fun executeIfCurrent(proposal: Proposal): String? {
        val admitted = synchronized(this) { isCurrent(proposal) && (proposal.sessionId == null || AppForeground.isForeground) }
        if (!admitted) {
            proposal.onDiscard?.invoke()
            return null
        }
        return proposal.execute()
    }

    /** A returned string may itself report failure. Never turn every result into a checkmark. */
    suspend fun executeAndPublish(proposal: Proposal, auto: Boolean = false) {
        val result = try {
            executeIfCurrent(proposal) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Action failed: ${e.message ?: e.javaClass.simpleName}"
        }
        publishResultIfCurrent(proposal, (if (auto) "Auto action result: " else "Action result: ") + result)
    }

    fun decline(proposal: Proposal) {
        proposal.onDiscard?.invoke()
        publishResultIfCurrent(proposal, "Not executed: approval declined.")
    }

    // Serialize the final check with reset as well: a completed old action must not
    // recreate a chat bubble after the reset coordinator cleared the conversation.
    @Synchronized
    fun publishResultIfCurrent(proposal: Proposal, content: String) {
        if (isCurrent(proposal)) {
            ChatStore.add(proposal.sessionId ?: ChatStore.activeSessionId.value,
                ChatMessage(role = ChatMessage.Role.SYSTEM, content = content))
        }
    }

    /** Invalidates old approvals before withdrawing their visible confirmation surfaces. */
    @Synchronized
    fun resetForDemo() {
        demoGeneration++
        NotifConfirm.resetForDemo()
        OverlayConfirm.resetForDemo()
    }

    /** Keep the gate closed while old foreground/background coroutines drain. */
    @Synchronized
    fun beginDemoReset() {
        demoResetInProgress = true
        resetForDemo()
    }

    /** Proposals created by a cancelling old turn are stale too when the gate reopens. */
    @Synchronized
    fun finishDemoReset() {
        resetForDemo()
        demoResetInProgress = false
    }
}
