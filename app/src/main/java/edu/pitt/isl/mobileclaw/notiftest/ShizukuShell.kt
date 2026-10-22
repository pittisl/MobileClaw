package edu.pitt.isl.mobileclaw.notiftest

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * [PrivilegedShell] backed by Shizuku's user-service mechanism.
 *
 * Shizuku runs [PrivilegedService] as uid 2000 and hands us a Binder to it; this class
 * is the thin translation from that Binder to the app-side contract. It holds no
 * policy — [ShellGate] decides what may run and whether a human confirmed it, and
 * [ShellCommandPolicy] independently re-decides inside the privileged process.
 */
class ShizukuShell(private val service: IPrivilegedService) : PrivilegedShell {

    /**
     * Shizuku can be started from ADB (uid 2000) or from root, and reports which. We
     * ask for shell and are content with it — none of these operations needs root.
     */
    override val level: ShellLevel =
        if (runCatching { Shizuku.getUid() }.getOrDefault(2000) == 0) ShellLevel.ROOT else ShellLevel.SHELL

    override suspend fun exec(argv: List<String>): ShellResult = withContext(Dispatchers.IO) {
        call { service.exec(argv.toTypedArray()) }
    }

    override suspend fun write(path: String, content: String): ShellResult = withContext(Dispatchers.IO) {
        call { service.writeFile(path, content) }
    }

    override suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching { service.asBinder().pingBinder() && service.identity().isNotBlank() }
            .getOrDefault(false)
    }

    /**
     * A dead binder is an ordinary outcome here, not an exception to propagate: the
     * user can stop Shizuku, or reboot, between one command and the next. Turning that
     * into a [ShellResult] keeps it on the same path as any other failure — reported to
     * the model, recorded in the audit trail.
     */
    private inline fun call(block: () -> android.os.Bundle?): ShellResult = try {
        val bundle = block()
        if (bundle == null) {
            ShellResult(PrivilegedService.FAILED_EXIT, "", "privileged service returned nothing")
        } else {
            ShellResult(
                exitCode = bundle.getInt(PrivilegedService.KEY_EXIT, PrivilegedService.FAILED_EXIT),
                stdout = bundle.getString(PrivilegedService.KEY_STDOUT).orEmpty(),
                stderr = bundle.getString(PrivilegedService.KEY_STDERR).orEmpty()
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "privileged call failed", t)
        ShizukuBridge.onServiceLost()
        ShellResult(PrivilegedService.FAILED_EXIT, "", "privileged service unreachable: ${t.message}")
    }

    private companion object {
        const val TAG = "MobileClaw-SZ"
    }
}

/**
 * Owns the Shizuku connection: probe, permission, bind, and — the part that is easy to
 * forget — tearing the backend back out of [ShellRegistry] when it goes away.
 *
 * Everything here is defensive about Shizuku being absent. The overwhelmingly common
 * case is a device with no Shizuku installed at all, where the classes are present
 * (they ship in our APK) but no service ever answers. That must degrade to "no
 * privileged backend", never to a crash on startup, so every entry point catches
 * broadly — `Shizuku` throws `IllegalStateException` from several methods when the
 * binder was never received.
 */
object ShizukuBridge {

    private const val TAG = "MobileClaw-SZ"
    private const val PERMISSION_REQUEST_CODE = 4001

    /** Where the user gets sent when Shizuku isn't installed. */
    const val SHIZUKU_PLAY_URL = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received (version=${runCatching { Shizuku.getVersion() }.getOrNull()})")
        bindIfPermitted()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku binder died")
        onServiceLost()
    }

    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindUserService()
            else Log.i(TAG, "Shizuku permission denied")
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "privileged service connected with a dead binder")
                return
            }
            val service = IPrivilegedService.Stub.asInterface(binder)
            ShellRegistry.install(ShizukuShell(service))
            Log.i(TAG, "privileged backend ready: ${runCatching { service.identity() }.getOrNull()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "privileged service disconnected")
            onServiceLost()
        }
    }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService::class.java.name)
        )
            // Not a daemon: the privileged process should not outlive the app that asked
            // for it. A command server that survives our death is the ADB-daemon design
            // and its costs, which is precisely what choosing Shizuku avoided.
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /** Registers listeners and binds if Shizuku is already up and already permitted. */
    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        } catch (t: Throwable) {
            Log.i(TAG, "Shizuku not available: ${t.message}")
        }
    }

    /** True when the Shizuku service itself is running (regardless of our permission). */
    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Asks Shizuku for permission. Returns false when there is nothing to ask — no
     * service running, or a pre-v11 build whose permission model we don't support — so
     * the caller can explain instead of showing a dialog that will never resolve.
     */
    fun requestPermission(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            if (Shizuku.isPreV11()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
                true
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "permission request failed", t)
            false
        }
    }

    private fun bindIfPermitted() {
        if (hasPermission()) bindUserService()
        else Log.i(TAG, "Shizuku is running but permission is not granted yet")
    }

    private fun bindUserService() {
        try {
            if (Shizuku.getVersion() < 10) {
                Log.w(TAG, "Shizuku API ${Shizuku.getVersion()} is too old for user services (needs 10+)")
                return
            }
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to bind the privileged service", t)
            onServiceLost()
        }
    }

    /**
     * Drops the backend. Called from the death/disconnect callbacks *and* from
     * [ShizukuShell] when a call throws, because a binder can be dead for a whole
     * command before any callback fires — and a registry that still advertises a dead
     * backend is worse than an empty one: it turns "unavailable" into a failed command.
     */
    fun onServiceLost() {
        if (ShellRegistry.isConnected) {
            Log.i(TAG, "privileged backend lost")
            ShellRegistry.install(null)
        }
    }
}
