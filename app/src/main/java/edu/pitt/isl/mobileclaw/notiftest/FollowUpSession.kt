package edu.pitt.isl.mobileclaw.notiftest

/** A background result belongs to the conversation present when its notification arrived. */
internal class FollowUpSession private constructor(
    val id: String,
    private val generation: Long,
) {
    companion object {
        fun capture(): FollowUpSession = synchronized(ChatStore) {
            val id = ChatStore.activeSessionId.value
            FollowUpSession(id, PhotoSelectionStore.generation(id))
        }
    }

    /** Switching chats is fine; clearing/deleting the original chat invalidates the result. */
    fun publish(epoch: Long, block: () -> Unit): Boolean {
        var published = false
        DemoReset.publishIfCurrent(epoch) {
            // ChatStore.clear/deleteSession use this same lock before revoking the
            // photo generation. Check and publication must be one operation.
            synchronized(ChatStore) {
                if (PhotoSelectionStore.generation(id) == generation) {
                    block()
                    published = true
                }
            }
        }
        return published
    }
}
