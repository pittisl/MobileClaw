package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

/** Cancels both a blocked connect and an in-progress response read with its owning turn. */
internal suspend fun Call.executeCancellable(): Response {
    val call = this
    // A plain child Job completes its cancellation handlers synchronously, even when
    // the owning dispatcher has a single thread currently blocked inside OkHttp.
    val cancellation = Job(currentCoroutineContext()[Job])
    cancellation.invokeOnCompletion { call.cancel() }
    try {
        val response = execute()
        val body = response.body
        if (body == null) {
            cancellation.cancel()
            return response
        }
        val source = object : ForwardingSource(body.source()) {
            override fun close() {
                try { super.close() } finally { cancellation.cancel() }
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
    } catch (failure: Throwable) {
        cancellation.cancel()
        throw failure
    }
}
