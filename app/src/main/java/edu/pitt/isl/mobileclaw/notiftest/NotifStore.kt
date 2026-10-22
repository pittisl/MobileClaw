package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Singleton in-memory buffer of captured notifications, shared between the
 * listener service (producer) and the activity (consumer).
 *
 * Why a singleton with StateFlow rather than LocalBroadcastManager or a bound
 * service? Because the listener service and the activity live in the same
 * process by default, so a shared object is the simplest correct answer. If
 * we later split them into separate processes for isolation, we'd swap this
 * out for a ContentProvider or AIDL.
 *
 * Capacity is capped so the UI doesn't grow unbounded during long test runs.
 */
object NotifStore {

    private const val MAX_ENTRIES = 500

    private val _events = MutableStateFlow<List<CapturedNotif>>(emptyList())
    val events: StateFlow<List<CapturedNotif>> = _events

    @Synchronized
    fun add(n: CapturedNotif) {
        val next = (_events.value + n).takeLast(MAX_ENTRIES)
        _events.value = next
    }

    /** Active (posted) notifications, optionally filtered — used by agent tools. Newest first. */
    fun query(packageFilter: String? = null, messagingOnly: Boolean = false, limit: Int = 25): List<CapturedNotif> =
        _events.value.asReversed()
            .filter { it.posted }
            .filter { !messagingOnly || it.isMessaging }
            .filter { n ->
                packageFilter == null ||
                    n.packageName.contains(packageFilter, ignoreCase = true) ||
                    n.appName.contains(packageFilter, ignoreCase = true)
            }
            .take(limit)

    /** Most-recent active notification matching a free-text hint (app / title / text) — for action tools. */
    fun findByHint(hint: String): CapturedNotif? {
        val h = hint.trim()
        val active = _events.value.asReversed().filter { it.posted }
        if (h.isBlank()) return active.firstOrNull()
        return active.firstOrNull { n ->
            n.appName.contains(h, true) || n.packageName.contains(h, true) ||
                n.title.contains(h, true) || n.text.contains(h, true)
        }
    }

    @Synchronized
    fun clear() {
        _events.value = emptyList()
    }

    fun toJSON(): String {
        val arr = JSONArray()
        events.value.forEach { arr.put(it.toJSONObject()) }
        return JSONObject().apply { put("NotifStore", arr) }.toString()
    }
}