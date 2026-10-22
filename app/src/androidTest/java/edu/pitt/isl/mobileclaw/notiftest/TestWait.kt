package edu.pitt.isl.mobileclaw.notiftest

/**
 * Polls [condition] until it holds or [timeoutMs] elapses, failing with [message]
 * on timeout.
 *
 * [ActionLogStore.record] is deliberately non-suspend — it hands the insert to an
 * internal IO scope so callers on the main thread (the listener service, the confirm
 * dialog) never block. That means a test can't assert immediately after recording,
 * and a fixed `Thread.sleep` would be both slower and flakier than polling.
 */
fun awaitUntil(
    timeoutMs: Long = 5_000,
    pollMs: Long = 25,
    message: String = "condition not met",
    condition: () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(pollMs)
    }
    throw AssertionError("Timed out after ${timeoutMs}ms: $message")
}
