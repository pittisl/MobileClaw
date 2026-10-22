package edu.pitt.isl.mobileclaw.notiftest

/**
 * The single door every privileged operation goes through.
 *
 * The threat this is built around is specific. Notification text is
 * attacker-controlled and flows, post-redaction, into the model's prompt — so any app
 * that can post a notification can aim text at the agent. Today the worst case is a
 * bad triage decision behind a confirm dialog. Once a shell backend exists the chain
 * becomes *hostile notification → prompt injection → command as uid 2000*, and the
 * confirm gate is the only thing standing in it.
 *
 * So the API is shaped to make the dangerous thing unrepresentable rather than merely
 * discouraged:
 *
 *  - [read] executes immediately, because a read has no unattended blast radius.
 *  - [modify] **cannot execute.** It has no parameter that would let it, and returns a
 *    string for the model rather than a result. All it can do is stage a
 *    [PendingAction.Proposal] with `allowFullAutomation = false`. There is no code
 *    path from a modifying privileged command to the backend that does not pass
 *    through a human saying yes.
 *
 * That last point is the whole design. "Route writes through PendingAction" as a
 * convention is one forgetful call site away from being false; as the only signature
 * available, it stays true. In particular it means Full Automation Mode — which
 * exists so the agent can dismiss notifications unattended — is not also a standing
 * licence for `rm -rf` reached by prompt injection.
 */
object ShellGate {

    /**
     * Runs a read-only privileged command and returns output for the model.
     *
     * [operation] and [source] are for the audit trail; [argv] must already have been
     * vetted by whatever policy owns those paths ([FsPolicy] for `file_system`). This
     * re-checks the *vector* — the residual things argv framing can't express — but it
     * deliberately does not re-derive path policy: one owner per question.
     */
    suspend fun read(source: String, operation: String, argv: List<String>): String {
        val shell = available() ?: return unavailable()
        FsPolicy.validateArgv(argv)?.let { return malformed(it) }
        return runAndRecord(shell, source, operation, argv).forModel()
    }

    /**
     * The same read, returning the result instead of prose — for callers that parse the
     * output rather than hand it to the model (`memory_monitor` reads `ps`).
     *
     * Null means **no backend**, and deliberately does not call [unavailable]: a caller
     * with an unprivileged fallback should quietly take it, not ask the user to install
     * Shizuku for something it can still do. The caller that wants the capability
     * signal is the one calling [read].
     *
     * Only reads get this shape. A `modifyResult` would be exactly the "run it
     * directly" overload the gate exists to not have.
     */
    suspend fun readResult(source: String, operation: String, argv: List<String>): ShellResult? {
        val shell = available() ?: return null
        // Constant argv at every call site today, so this can only fire on a bug — but
        // the gate does not get to assume its callers are correct.
        FsPolicy.validateArgv(argv)?.let { return null }
        return runAndRecord(shell, source, operation, argv)
    }

    /**
     * Stages a modifying privileged command. Returns what the model should say while
     * it waits — never a result, because nothing has run.
     *
     * The availability check happens *here*, before the user is asked, so a missing
     * backend doesn't produce a confirm dialog for something that cannot happen.
     */
    fun modify(
        source: String,
        operation: String,
        argv: List<String>,
        description: String,
        reply: String,
        /**
         * How successful output is rendered for the model — a caller that knows the
         * command's output format (the AppFunctions dump) can make it readable. Rendering
         * only: it sees a string after the command has run and cannot influence whether
         * it runs, so the gate's property is untouched. The audit trail gets the raw output.
         */
        present: (String) -> String = { it },
    ): String {
        if (!ShellRegistry.isConnected) return unavailable()
        FsPolicy.validateArgv(argv)?.let { return malformed(it) }
        PendingAction.propose(proposalFor(source, operation, argv, description, present))
        return reply
    }

    /** AppFunctions exposes its deferred state without parsing the tool's English text. */
    fun modifyWithReceipt(
        source: String,
        operation: String,
        argv: List<String>,
        description: String,
        reply: String,
        present: (String) -> String = { it },
        isSuccessful: (ShellResult) -> Boolean = { it.ok },
    ): ToolInvocation {
        if (!ShellRegistry.isConnected) return ToolInvocation.NotExecuted(unavailable())
        FsPolicy.validateArgv(argv)?.let { return ToolInvocation.NotExecuted(malformed(it)) }
        return if (PendingAction.propose(proposalFor(source, operation, argv, description, present, isSuccessful)))
            ToolInvocation.AwaitingConfirmation(description, reply)
        else ToolInvocation.NotExecuted("No confirmation could be shown; the function was not executed.")
    }

    /**
     * Stages a file write. Content travels as its own payload, never as an argv
     * element and never through `echo` — see [PrivilegedShell.write].
     */
    fun modifyWrite(
        source: String,
        path: String,
        content: String,
        description: String,
        reply: String
    ): String {
        if (!ShellRegistry.isConnected) return unavailable()
        PendingAction.propose(writeProposalFor(source, path, content, description))
        return reply
    }

    /**
     * The proposal a modifying command becomes. Split out from [modify] because this is
     * where the load-bearing flag lives, and a pure function is something a JVM test
     * can assert on without a device, a backend, or a confirm dialog.
     */
    internal fun proposalFor(
        source: String,
        operation: String,
        argv: List<String>,
        description: String,
        present: (String) -> String = { it },
        isSuccessful: (ShellResult) -> Boolean = { it.ok },
    ) = PendingAction.Proposal(
        description = description,
        // Never true here, and not a parameter: see the class doc.
        allowFullAutomation = false,
        execute = {
            val shell = available()
            if (shell == null) unavailable()
            else runAndRecord(shell, source, operation, argv, isSuccessful).let { r -> if (r.ok && r.stdout.isNotBlank()) present(r.stdout) else r.forModel() }
        }
    )

    internal fun writeProposalFor(
        source: String,
        path: String,
        content: String,
        description: String
    ) = PendingAction.Proposal(
        description = description,
        allowFullAutomation = false,
        execute = {
            val shell = available()
            if (shell == null) {
                unavailable()
            } else {
                val result = shell.write(path, content)
                // Byte count, never the content: the audit trail is what DataExport
                // hands to the user, so file bodies must not land in it.
                ActionLogStore.record(
                    appName = source, key = "write",
                    actionTitle = "write ${content.length} bytes → $path",
                    suppliedText = null,
                    result = auditLine(result),
                    success = result.ok
                )
                result.forModel()
            }
        }
    )

    private suspend fun runAndRecord(
        shell: PrivilegedShell,
        source: String,
        operation: String,
        argv: List<String>,
        isSuccessful: (ShellResult) -> Boolean = { it.ok },
    ): ShellResult {
        val result = shell.exec(argv)
        ActionLogStore.record(
            appName = source, key = operation,
            // Display join only. The argv that crossed the bridge is never
            // reconstructed from this string — it is for a human reading the export.
            actionTitle = argv.joinToString(" "),
            suppliedText = null,
            result = auditLine(result),
            success = isSuccessful(result)
        )
        return result
    }

    /** Exit status plus a bounded excerpt — the log is not a place to store file dumps. */
    private fun auditLine(result: ShellResult): String {
        val detail = (if (result.ok) result.stdout else result.stderr).trim()
        val excerpt = if (detail.length > AUDIT_EXCERPT_CHARS) {
            detail.take(AUDIT_EXCERPT_CHARS) + "… (${detail.length} chars)"
        } else {
            detail
        }
        return if (excerpt.isBlank()) "exit ${result.exitCode}" else "exit ${result.exitCode}: $excerpt"
    }

    /**
     * The backend, or null — re-read on every call rather than cached, because a
     * reboot takes it away without taking the app with it.
     */
    private fun available(): PrivilegedShell? = ShellRegistry.current()

    /**
     * No backend. Signals the UI to walk the user through setup *and* tells the model,
     * the same two-part answer every other capability-gated tool gives.
     */
    private fun unavailable(): String {
        PermissionSignal.request(Capability.PRIVILEGED_SHELL)
        return Capability.unavailable(Capability.PRIVILEGED_SHELL)
    }

    /**
     * A vector that argv framing can't carry (a NUL, an empty element). Phrased for the
     * model, and phrased so it doesn't invite a retry with different spelling.
     */
    private fun malformed(reason: String) =
        "[shell_denied: $reason] The command was not sent. Tell the user what was " +
            "refused; re-sending the same command will not change this answer."

    private const val AUDIT_EXCERPT_CHARS = 400
}
