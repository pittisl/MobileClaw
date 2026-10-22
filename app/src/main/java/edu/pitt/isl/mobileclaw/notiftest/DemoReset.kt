package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** One reset shared by the UI and listener; cancellation must settle before chat is cleared. */
object DemoReset {
    private val mutex = Mutex()
    private val _resetting = MutableStateFlow(false)
    val resetting: StateFlow<Boolean> = _resetting
    @Volatile internal var generation = 0L
        private set

    @Synchronized
    internal fun publishIfCurrent(epoch: Long, publish: () -> Unit) {
        if (epoch == generation && !_resetting.value) publish()
    }

    @Volatile internal var hasReset = false
        private set

    suspend fun run(sessionId: String = ChatStore.activeSessionId.value, stopChat: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        // Finish a started reset even if its Activity/ViewModel goes away halfway through.
        withContext(NonCancellable) {
            synchronized(this@DemoReset) { generation++; _resetting.value = true }
            val listener = NotifListener.instance
            hasReset = true
            try {
                PendingAction.beginDemoReset()
                listener?.beginDemoReset()
                stopChat()
                listener?.drainDemoRuns()
                ChatStore.clear(sessionId)
                NotifStore.clear()
            } finally {
                try {
                    listener?.finishDemoReset()
                    // A new listener may have connected while the old jobs drained.
                    NotifListener.instance?.takeIf { it !== listener }?.finishDemoReset()
                } finally {
                    try { PendingAction.finishDemoReset() }
                    finally {
                        _resetting.value = false
                        mutex.unlock()
                    }
                }
            }
        }
    }
}
