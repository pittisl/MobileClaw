package edu.pitt.isl.mobileclaw.notiftest.appfunctions

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The self-call: find our own `ping` in the platform's registry, enable it, ask the
 * platform to execute it, read `pong` back. The caller side of AppFunctions
 * is otherwise closed to an unprivileged app, so this is the whole of what the
 * API lets MobileClaw demonstrate today — and it is a real round trip through the
 * system service and the platform's index, not a method call.
 *
 * Only on API 36+: the platform service does not exist below it, and `AppFunctionManager`
 * throws rather than degrades. The result is a string for a chat bubble or a log line;
 * nothing here is a `Tool` and nothing is exposed to the model — a bus with one endpoint,
 * ourselves, is a demonstration and not a capability.
 */
object AppFunctionPing {

    private const val TAG = "MobileClaw-AF"

    /** The identifier the compiler assigns: fully-qualified class, `#`, method. */
    const val PING_ID = "edu.pitt.isl.mobileclaw.notiftest.appfunctions.MobileClawFunctions#ping"

    private const val TIMEOUT_MS = 15_000L

    sealed interface Outcome {
        data class Pong(val reply: String, val elapsedMs: Long) : Outcome
        data class Failed(val reason: String) : Outcome
        data object Unsupported : Outcome
    }

    suspend fun run(context: Context, message: String = "hello"): Outcome {
        if (Build.VERSION.SDK_INT < 36) return Outcome.Unsupported
        val started = System.currentTimeMillis()
        return try {
            withTimeout(TIMEOUT_MS) {
                val manager = AppFunctionManager.getInstance(context)
                    ?: return@withTimeout Outcome.Failed("AppFunctionManager unavailable on this device")

                // Build parameters from the KSP-generated metadata for `ping`.
                // Platform registry discovery is diagnostic only: an empty result
                // must not prevent a self-call using the compiled function inventory.
                val inventory = `$MobileClawFunctions_AppFunctionInventory`()
                val fn = inventory.functionIdToMetadataMap[PING_ID]
                    ?: return@withTimeout Outcome.Failed("ping missing from the compiled inventory")
                val visible = runCatching {
                    withTimeout(3_000) {
                        manager.observeAppFunctions(AppFunctionSearchSpec(setOf(context.packageName))).first()
                            .flatMap { it.appFunctions }.map { it.id }
                    }
                }.getOrElse { emptyList() }
                Log.i(TAG, "registry lists ${visible.size} function(s) for us: $visible")

                // Enabled at runtime for the demo rather than in the annotation, so the
                // function is not indexed as callable on an ordinary install.
                manager.setAppFunctionEnabled(PING_ID, AppFunctionManager.APP_FUNCTION_STATE_ENABLED)

                val params = AppFunctionData.Builder(fn.parameters, inventory.componentsMetadata)
                    .setString("message", message).build()
                val request = ExecuteAppFunctionRequest(context.packageName, PING_ID, params)

                // The Jetpack client resolves the function through its AppSearch reader before
                // calling, and that reader sees nothing for our own package on the Pixel 7 —
                // FunctionNotFoundException — while the platform service executes the same
                // identifier for uid 2000 without complaint. So the call goes to the
                // framework `AppFunctionManager` directly, with the request the Jetpack
                // classes marshalled; the service side still runs through the Jetpack
                // inventory, which is what the demo is meant to show.
                val reply = executeViaPlatform(context, request.toPlatformExecuteAppFunctionRequest())
                val elapsed = System.currentTimeMillis() - started
                Log.i(TAG, "ping → $reply in $elapsed ms (registry saw ${visible.size})")
                Outcome.Pong(reply, elapsed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ping failed", e)
            Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @RequiresApi(36)
    private suspend fun executeViaPlatform(
        context: Context,
        request: android.app.appfunctions.ExecuteAppFunctionRequest,
    ): String = suspendCancellableCoroutine { cont ->
        val manager = context.getSystemService(android.app.appfunctions.AppFunctionManager::class.java)
            ?: run { cont.resumeWithException(IllegalStateException("no platform AppFunctionManager")); return@suspendCancellableCoroutine }
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        manager.executeAppFunction(
            request, context.mainExecutor, signal,
            object : OutcomeReceiver<android.app.appfunctions.ExecuteAppFunctionResponse, android.app.appfunctions.AppFunctionException> {
                override fun onResult(result: android.app.appfunctions.ExecuteAppFunctionResponse) {
                    val value = result.resultDocument
                        .getPropertyString(android.app.appfunctions.ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE)
                    if (value == null) cont.resumeWithException(IllegalStateException("success with no return value"))
                    else cont.resume(value)
                }
                override fun onError(error: android.app.appfunctions.AppFunctionException) {
                    cont.resumeWithException(error)
                }
            }
        )
    }
}
