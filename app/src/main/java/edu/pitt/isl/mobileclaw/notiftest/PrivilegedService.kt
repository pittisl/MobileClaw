package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.os.Bundle
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The privileged half of the bridge, running in its own process as the ADB
 * shell user. Shizuku instantiates this from our APK — the code is ours, the uid is
 * not.
 *
 * **This process shares nothing with the app.** No Room, no stores, no
 * `MobileClawApp.onCreate`; touching `ActionLogStore` from here would silently do
 * nothing, because that singleton lives in the other process. Audit logging therefore
 * stays app-side in [ShellGate], where the database actually is.
 *
 * The class is instantiated reflectively by the Shizuku server, so the constructors
 * and the class itself must survive shrinking — hence [Keep]. (Release builds set
 * `isMinifyEnabled = false` today, so this is insurance against the day they don't,
 * and the failure it prevents is a `ClassNotFoundException` in a process with no
 * debugger attached.)
 */
@Keep
class PrivilegedService : IPrivilegedService.Stub {

    /** Required by the Shizuku server. */
    @Keep
    constructor() {
        Log.i(TAG, "privileged service started: ${identity()}")
    }

    /** Preferred when available (Shizuku API v13+); the context is not used yet. */
    @Keep
    constructor(context: Context) : this()

    override fun destroy() {
        Log.i(TAG, "privileged service destroyed")
        System.exit(0)
    }

    override fun identity(): String = "uid=${Os.getuid()} pid=${Os.getpid()}"

    override fun exec(argv: Array<String>?): Bundle {
        val args = argv?.toList().orEmpty()

        // Re-checked here even though the app already vetted it. The app is the side a
        // prompt injection talks to; this side is the one holding the privilege, and it
        // must not accept an operation the app never intended to offer.
        ShellCommandPolicy.reject(args)?.let { reason ->
            Log.w(TAG, "refused: $reason (argv[0]=${args.firstOrNull()})")
            return result(REFUSED_EXIT, "", "refused by privileged bridge: $reason")
        }

        return try {
            // ProcessBuilder execs directly — there is no shell in this path, so nothing
            // globs, splits or interprets any byte of these arguments.
            val process = ProcessBuilder(args).start()
            // Read both streams before waiting: a command that fills the pipe buffer
            // blocks forever if nobody is draining it, and waitFor would never return.
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(ShellCommandPolicy.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return result(TIMEOUT_EXIT, ShellCommandPolicy.cap(stdout),
                    "timed out after ${ShellCommandPolicy.TIMEOUT_MS} ms")
            }
            result(process.exitValue(), ShellCommandPolicy.cap(stdout), ShellCommandPolicy.cap(stderr))
        } catch (t: Throwable) {
            // Never let this throw across the Binder: a RemoteException on the app side
            // says nothing about what went wrong, and this is prompt surface.
            Log.w(TAG, "exec failed", t)
            result(FAILED_EXIT, "", t.message ?: t.javaClass.simpleName)
        }
    }

    override fun writeFile(path: String?, content: String?): Bundle {
        if (path.isNullOrBlank()) return result(FAILED_EXIT, "", "no path given")
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            // Plain IO as uid 2000. Never `echo`, never a redirect, never an argv
            // element — the content does not pass through anything that parses it.
            file.writeText(content.orEmpty())
            result(0, "", "")
        } catch (t: Throwable) {
            Log.w(TAG, "write failed", t)
            result(FAILED_EXIT, "", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun result(exitCode: Int, stdout: String, stderr: String) = Bundle().apply {
        putInt(KEY_EXIT, exitCode)
        putString(KEY_STDOUT, stdout)
        putString(KEY_STDERR, stderr)
    }

    companion object {
        private const val TAG = "MobileClaw-PS"

        const val KEY_EXIT = "exit"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"

        /**
         * Exit codes for failures that never reached a real process. Distinct from any
         * plausible command status so the audit trail can tell "the bridge said no"
         * from "the command ran and failed".
         */
        const val REFUSED_EXIT = -13
        const val TIMEOUT_EXIT = -14
        const val FAILED_EXIT = -15
    }
}
