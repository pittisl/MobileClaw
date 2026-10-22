package edu.pitt.isl.mobileclaw.notiftest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
/**
 * How much privilege a backend actually has. [SHELL] — the ADB shell user, uid 2000,
 * SELinux domain `shell` — is the design target for everything in privileged tooling:
 * cross-app `/proc`, `am force-stop`, shared-storage writes. Nothing on that list
 * needs [ROOT], so root stays strictly optional and is never required.
 */
enum class ShellLevel { NONE, SHELL, ROOT }

/**
 * Output of a privileged command, including both streams and the exit status.
 * Callers can distinguish a successful empty response from a failed command.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val ok: Boolean get() = exitCode == 0

    /** What the model reads. Errors are prompt surface, so stderr is not swallowed. */
    fun forModel(): String = when {
        ok && stdout.isBlank() -> "(command succeeded, no output)"
        ok -> stdout
        stderr.isNotBlank() -> "Command failed (exit $exitCode): $stderr"
        else -> "Command failed (exit $exitCode)."
    }
}

/**
 * Contract for a backend that runs commands at elevated privilege. [ShizukuShell]
 * implements it through a Binder user service.
 *
 * The contract takes an argument vector, never a shell command string. In
 * `["rm", "-rf", "--", path]`, the path remains one argument regardless of its bytes.
 * Joining the vector into a command passed to `sh` would reintroduce injection.
 */
interface PrivilegedShell {

    val level: ShellLevel

    /** Runs [argv] directly — no shell, no globbing, no word splitting. */
    suspend fun exec(argv: List<String>): ShellResult

    /**
     * Writes [content] to [path]. Separate from [exec] on purpose: file content must
     * never become an argv element or reach `echo`. The backend frames it as its own
     * payload and streams it into the target.
     */
    suspend fun write(path: String, content: String): ShellResult

    /**
     * Cheap liveness probe. Every planned backend dies on reboot — the ADB daemon
     * outright, Shizuku until the user restarts it — so "resolved once at startup" is
     * not the same as "still there", and callers re-probe rather than assume.
     */
    suspend fun isAlive(): Boolean
}

/**
 * The currently connected backend, or none. Tools resolve it at use time because
 * they can be created before a backend is available, and a reboot can remove the
 * backend without closing the app.
 *
 * [ShizukuBridge] installs and clears the Shizuku backend as its connection changes.
 * With no connected backend, [level] is [ShellLevel.NONE] and privileged operations
 * report that they are unavailable. All commands still pass through [ShellGate].
 */
object ShellRegistry {

    @Volatile
    private var backend: PrivilegedShell? = null

    private val _state = MutableStateFlow(ShellLevel.NONE)

    /**
     * [level], observable. The grant arrives asynchronously — Shizuku's dialog, then a
     * bind — so a screen showing "not granted" has no return-to-foreground moment to
     * refresh on; it has to be told.
     */
    val state: StateFlow<ShellLevel> = _state

    /** The live backend, or null when nothing is connected. */
    fun current(): PrivilegedShell? = backend

    val level: ShellLevel get() = backend?.level ?: ShellLevel.NONE

    val isConnected: Boolean get() = level != ShellLevel.NONE

    /**
     * Installs [shell], or clears it with null. Called by whichever backend probe
     * succeeds — and by tests, which is the other reason this is settable rather than
     * resolved once in an initialiser.
     */
    @Synchronized
    fun install(shell: PrivilegedShell?) {
        backend = shell
        _state.value = level
    }
}
