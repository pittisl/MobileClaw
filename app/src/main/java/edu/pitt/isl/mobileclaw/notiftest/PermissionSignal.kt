package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bridge that lets a headless tool (no UI of its own) ask the Activity to start a
 * permission flow. A tool emits a [Capability] request; the Activity collects it
 * and shows a native onboarding prompt — an explanation plus a one-tap grant path
 * (a runtime permission dialog or a deep-link to the right Settings page) — so the
 * user gets a real grant path instead of a dead chat suggestion the model can't
 * act on.
 */
object PermissionSignal {
    private val _requests = MutableSharedFlow<Capability>(extraBufferCapacity = 4)
    val requests: SharedFlow<Capability> = _requests

    /** Ask the UI to walk the user through granting [capability]. */
    fun request(capability: Capability) {
        _requests.tryEmit(capability)
    }
}
