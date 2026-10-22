package edu.pitt.isl.mobileclaw.notiftest

/**
 * What the **privileged process** will agree to run, decided independently of the app.
 *
 * App-side checks cannot bind the privileged process. `FsPolicy` and the tool's
 * JSON schema constrain a cooperative model: they stop a well-behaved
 * agent from asking for the wrong thing. Neither binds the privileged end. A bug in
 * the app, a future tool that forgets to vet, or anything that talks the model into a
 * novel command would otherwise reach uid 2000 with whatever it liked, because the
 * only thing saying no lived on the side that had already been convinced.
 *
 * So the allowlist is re-applied where the privilege actually is, and it is an
 * allowlist rather than a denylist: the interesting commands are the ones nobody
 * thought to forbid.
 *
 * Pure JVM — no Android imports — for the usual reason ([FsPolicy], [HistoryBudget]):
 * this is where the hostile cases get tested, and they should not need a device, a
 * Shizuku install, or uid 2000 to run.
 */
object ShellCommandPolicy {

    /**
     * Every program the bridge may start, by basename. Deliberately short and boring:
     * it covers exactly what `file_system`, `memory_monitor` and `kill_process` offer.
     *
     * Notable absences that look harmless and are not: `sh`/`toybox`/`busybox` would
     * re-open the command-string hole from the other side, `chmod`/`chown` change who
     * else can reach a file, and `dd` is a general-purpose overwriter. Adding an entry
     * here is a security decision, not a convenience one.
     *
     * `ps` earns its place cheaply — it has no subcommands and its worst case is
     * listing processes, which is the entire point of `memory_monitor`. `am` does not,
     * and is handled by [ARGUMENT_RULES] instead.
     */
    private val ALLOWED = setOf(
        "ls", "cat", "stat", "find",   // reads
        "ps",                          // reads, cross-app — memory_monitor
        "mkdir", "rm", "mv", "cp"      // modifications, all confirmed app-side first
    )

    /**
     * Programs for which a basename is **not a fine enough unit of authority**, and the
     * arguments decide. A rule returns null to allow, or the reason to refuse.
     *
     * `am` is the reason this layer exists. `kill_process` wants two specific things from
     * it — `am force-stop <pkg>` and `am set-standby-bucket <pkg> restricted` — but the
     * basename also carries `am start` (launch any component with attacker-chosen extras
     * and URIs) and `am broadcast` (deliver a forged broadcast to any receiver on the
     * device). Putting `am` in [ALLOWED] would hand a prompt injection all four for the
     * price of the two we wanted, so the allowlist granularity moves down to the argument
     * vector for it.
     *
     * Note this does not contradict refusing `sh`: `/system/bin/am` *is* a shell script
     * (`cmd activity "$@"`), but the kernel hands it our argv element-for-element and
     * `"$@"` passes it through unsplit. The rule against shells is about argv[0] being
     * able to *reinterpret* our arguments as a command line, which this does not do.
     */
    private val ARGUMENT_RULES: Map<String, (List<String>) -> String?> = mapOf(
        "am" to ::rejectAm,
        "cmd" to ::rejectCmd,
    )

    /**
     * The app's own package, refused as a target of either `am` subcommand.
     *
     * For `force-stop` it is a command that erases the record of itself: [ShellGate]
     * writes the audit row *after* `exec` returns, in the app process, which would no
     * longer exist. It is also the target a prompt injection would pick to take out the
     * confirm dialog and the audit trail together.
     *
     * For `set-standby-bucket` the reasoning is different but lands in the same place:
     * restricting ourselves throttles the background triage loop, so the agent would be
     * disabling itself — a quiet denial of service that a hostile notification could ask
     * for in one sentence, and that would look like the app simply having stopped working.
     */
    private const val OWN_PACKAGE = "edu.pitt.isl.mobileclaw.notiftest"

    /** Android package names: dot-separated segments, each starting with a letter. */
    private val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    /**
     * The two vectors `am` may carry, and nothing else the program can do.
     *
     * Both are exact-length on purpose: `am force-stop --user 0 x` is plausible and would
     * be harmless, but every optional argument accepted here is one a future reader has
     * to re-reason about, and a rule that only checks `argv[1]` is walked past with
     * trailing arguments. The tools have no use for the options.
     *
     * **`set-standby-bucket` pins the bucket to `restricted`, not just the length.** The
     * subcommand also accepts `active`, `working_set`, `frequent`, `rare` and `never`,
     * and the first of those *raises* an app's privileges rather than lowering them —
     * so an unpinned last element would turn a throttling tool into one that grants
     * background freedom to anything the model can be talked into naming.
     */
    private fun rejectAm(argv: List<String>): String? = when {
        argv.size == 3 && argv[1] == "force-stop" -> checkTarget(argv[2], "force-stop")

        argv.size == 4 && argv[1] == "set-standby-bucket" ->
            if (argv[3] != RESTRICTED_BUCKET) {
                "the privileged bridge allows only the \"$RESTRICTED_BUCKET\" standby bucket"
            } else {
                checkTarget(argv[2], "restrict")
            }

        else -> "the privileged bridge allows only \"am force-stop <package>\" and " +
            "\"am set-standby-bucket <package> $RESTRICTED_BUCKET\""
    }

    /** The only standby bucket the bridge will set. See [rejectAm]. */
    private const val RESTRICTED_BUCKET = "restricted"

    private fun checkTarget(target: String, verb: String): String? {
        if (!PACKAGE_NAME.matches(target)) return "\"$target\" is not a package name"
        if (target == OWN_PACKAGE) return "MobileClaw will not $verb itself"
        return null
    }

    /**
     * `cmd app_function execute-app-function --package <pkg> --function <id> --parameters <json>`
     * and nothing else `cmd` reaches.
     *
     * `cmd` is the front door to every system service's shell interface — `cmd package`,
     * `cmd notification`, `cmd statusbar`, … — so the basename is worth nothing and the
     * whole vector is the unit: exactly nine elements, in this order, with `--user` and the
     * `set-enabled` subcommand refused. Enabling another app's disabled function is that
     * app's policy to keep, not ours to override; and this one command is already the
     * only route by which an unprivileged app calls *another* app's AppFunction on
     * Android 16, which is why it is worth a rule and also why it is confirmed every
     * time app-side (`ShellGate.modify`, never `read`).
     *
     * The function identifier is `<fully.qualified.Class>#<method>` as the AppFunctions
     * compiler assigns it; the parameters are a JSON object, which is the platform's own
     * wire format for this subcommand and crosses as one argv element — never a shell,
     * so its contents are bytes.
     */
    private fun rejectCmd(argv: List<String>): String? {
        val shape = argv.size == 9 && argv[1] == "app_function" && argv[2] == "execute-app-function" &&
            argv[3] == "--package" && argv[5] == "--function" && argv[7] == "--parameters"
        if (!shape) {
            return "the privileged bridge allows only \"cmd app_function execute-app-function " +
                "--package <pkg> --function <id> --parameters <json>\""
        }
        val pkg = argv[4]
        if (!PACKAGE_NAME.matches(pkg)) return "\"$pkg\" is not a package name"
        val id = argv[6]
        if (!FUNCTION_ID.matches(id)) return "\"$id\" is not an AppFunction identifier (Class#method)"
        val json = argv[8].trim()
        if (!json.startsWith("{") || !json.endsWith("}")) return "parameters must be a JSON object"
        return null
    }

    /** `<fully.qualified.Class>#<method>` — what the AppFunctions compiler emits. */
    private val FUNCTION_ID = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_$]*)+#[a-zA-Z][a-zA-Z0-9_]*")

    /**
     * Output cap. Binder transactions share a ~1 MB buffer per process, and blowing it
     * throws `TransactionTooLargeException` — so an unbounded `cat` of a video would
     * take down the call rather than return a big string. 64 KB is far more than a
     * model can use and far less than the limit.
     */
    const val MAX_OUTPUT_BYTES = 64 * 1024

    /**
     * A command that never exits would hold a Binder thread forever, and the app would
     * show a spinner with nothing behind it. `find /` on a full card is the realistic
     * way to hit this, not malice.
     */
    const val TIMEOUT_MS = 30_000L

    /** Null when [argv] may run; otherwise why it may not, phrased for the audit trail. */
    fun reject(argv: List<String>): String? {
        if (argv.isEmpty()) return "empty command"
        if (argv.any { it.isEmpty() }) return "empty argument in command"
        // NUL truncates the argument inside execve, so what runs would differ from what
        // was vetted. A space, by contrast, is an ordinary character in a path and stays
        // allowed — argv framing exists precisely so it needs no escaping.
        if (argv.any { it.contains('\u0000') }) return "argument contains a NUL byte"

        // Basename, so neither "/system/bin/rm" nor a relative "bin/rm" can smuggle a
        // different program past a whole-string comparison.
        val program = argv[0].substringAfterLast('/')
        if (program.isEmpty()) return "no program named"

        // Argument rules are consulted first and are the *whole* answer for the
        // programs they cover, so nothing has to be listed in both places — a program
        // that is in ALLOWED as well would silently get the looser of the two checks.
        ARGUMENT_RULES[program]?.let { return it(argv) }

        if (program !in ALLOWED) {
            return "\"$program\" is not an allowed program for the privileged bridge"
        }
        return null
    }

    /**
     * Truncates to [MAX_OUTPUT_BYTES] and says so. Silent truncation would be worse
     * than the cap: the model would treat a partial listing as complete.
     */
    fun cap(output: String): String =
        if (output.length <= MAX_OUTPUT_BYTES) {
            output
        } else {
            output.take(MAX_OUTPUT_BYTES) +
                "\n… output truncated at $MAX_OUTPUT_BYTES characters (${output.length} total)"
        }
}
