package edu.pitt.isl.mobileclaw.notiftest

import androidx.test.core.app.ApplicationProvider
import edu.pitt.isl.mobileclaw.notiftest.skills.performance.KillProcessTool
import edu.pitt.isl.mobileclaw.notiftest.skills.performance.MemoryMonitorTool
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only test that exercises the **real** privileged bridge end to end: our
 * [PrivilegedService] actually running as uid 2000, reached over Binder.
 *
 * It is opt-in by construction rather than by an `@Ignore`: every test here is
 * `assumeTrue`-gated on Shizuku being installed, running, and having authorised us, so
 * on an ordinary device or CI machine the whole class reports as skipped instead of
 * failing for reasons that have nothing to do with the code. That is the same rule
 * live-model testing applies to the live-LLM smoke test.
 *
 * To make it actually run:
 *
 * ```bash
 * # 1. install Shizuku and start its server as the shell user
 * adb install shizuku.apk
 * LIB=$(adb shell pm path moe.shizuku.privileged.api | sed 's|package:||; s|/base.apk||')
 * adb shell "$LIB/lib/x86_64/libshizuku.so"
 * # 2. launch MobileClaw and accept Shizuku's authorisation prompt (it is Shizuku's own
 * #    dialog — `pm grant` does not work, the server tracks authorisation itself)
 * # 3. run (connectedAndroidTest has no --tests; it filters via runner arguments).
 * /gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=edu.pitt.isl.mobileclaw.notiftest.ShizukuLiveTest
 * ```
 *
 * What it is here to catch is everything the JVM tests structurally cannot: that the
 * AIDL round-trips, that the service class is findable and constructible by the
 * Shizuku server in a process with none of our `Application` setup, and that the
 * result Bundle maps back to a [ShellResult] the way [ShizukuShell] assumes.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuLiveTest {

    @Before
    fun requireLiveBackend() {
        assumeTrue("Shizuku is not running", ShizukuBridge.isShizukuRunning())
        assumeTrue("MobileClaw is not authorised in Shizuku", ShizukuBridge.hasPermission())
        ShizukuBridge.requestPermission() // binds the user service if not already bound
        awaitUntil(timeoutMs = 15_000, message = "the privileged service never connected") {
            ShellRegistry.isConnected
        }
    }

    @Test
    fun theServiceRunsElevatedAndReportsWhichLevel() = runBlocking {
        // The entire point of the bridge: a process we are not, running our code. Which
        // level depends on how the operator started Shizuku — ADB gives uid 2000, root
        // gives uid 0 — and both are legitimate, so the assertion is "elevated and
        // honest about it" rather than a hard-coded SHELL. Nothing in privileged tooling needs
        // root; it is simply not ours to refuse if that is how Shizuku was started.
        val shell = ShellRegistry.current()!!
        assertTrue("must be elevated, was ${shell.level}", shell.level != ShellLevel.NONE)
        assertTrue("the backend should answer a liveness probe", shell.isAlive())
    }

    @Test
    fun aReadCommandRunsAndReturnsOutput() = runBlocking {
        val result = ShellGate.read("test", "ls", listOf("ls", "--", "/sdcard"))
        assertTrue("expected a directory listing, got: $result", result.isNotBlank())
        assertTrue("should not have refused: $result", !result.startsWith("[shell_denied"))
        assertTrue("should not report unavailable: $result", !result.startsWith("[capability_unavailable"))
    }

    @Test
    fun theAllowlistIsEnforcedInsideThePrivilegedProcess() = runBlocking {
        // Bypasses ShellGate deliberately and talks to the backend directly, which is
        // what a bug or a future careless tool would effectively do. The refusal must
        // come from the privileged side, since that is the only side an attacker who
        // got past the app has not already talked to.
        val shell = ShellRegistry.current()!!

        val result = shell.exec(listOf("sh", "-c", "id"))

        assertEquals(PrivilegedService.REFUSED_EXIT, result.exitCode)
        assertTrue("expected a refusal, got: $result", result.stderr.contains("not an allowed program"))
    }

    @Test
    fun psSeesProcessesTheAppItselfCannot() = runBlocking {
        // The reason memory_monitor wanted the bridge. SELinux blocks untrusted_app from
        // reading other processes' /proc, and getRunningAppProcesses() has returned only
        // our own process since API 22 — so seeing anything here that isn't us is the
        // whole result. Asserted against the parser rather than the raw text, because
        // "ps ran" and "we can read what ps printed" are different claims.
        val result = ShellGate.readResult("test", "ps", listOf("ps", "-A", "-o", "RSS,NAME"))
        assertTrue("no backend, despite the assume in @Before", result != null)
        assertTrue("ps failed: ${result!!.stderr}", result.ok)

        val processes = PsOutput.parse(result.stdout)
        assertTrue("expected many processes, parsed ${processes.size}", processes.size > 10)
        assertTrue(
            "everything parsed was our own process — the bridge bought nothing",
            processes.any { it.packageName != "edu.pitt.isl.mobileclaw.notiftest" }
        )
        assertTrue(
            "kernel threads should have been dropped",
            processes.none { it.processName.startsWith("[") }
        )
    }

    @Test
    fun theRestOfAmIsRefusedInsideThePrivilegedProcess() = runBlocking {
        // `am` is allowed for exactly one subcommand, and the check that matters is the
        // one on the privileged side: the app-side check is the side a prompt injection
        // is already talking to. `am start` is the payload worth testing — it would
        // launch any component with attacker-chosen extras.
        val shell = ShellRegistry.current()!!

        val started = shell.exec(listOf("am", "start", "-a", "android.intent.action.VIEW"))
        assertEquals(PrivilegedService.REFUSED_EXIT, started.exitCode)
        assertTrue("expected a refusal, got: $started", started.stderr.contains("force-stop"))

        // …and the one form kill_process needs is not refused. Targets ourselves-adjacent
        // nothing: a package that does not exist, so a real force-stop is harmless.
        // am reports success either way, which is why KillProcessTool says so out loud.
        val stopped = shell.exec(listOf("am", "force-stop", "com.example.absent.mobileclaw"))
        assertTrue("force-stop must not be refused: ${stopped.stderr}", stopped.exitCode != PrivilegedService.REFUSED_EXIT)
    }

    @Test
    fun memoryMonitorPrefersTheBridgeOverTheCollectorScript() = runBlocking {
        // The tool end of the same path — everything between `ps` and the text the model
        // reads. Asserting on the metric is what makes this more than a smoke test: RSS
        // can only have come from `ps`, so a regression that silently fell back to the
        // collector script (or to reading our own /proc) fails here rather than
        // producing plausible-looking numbers from a source nobody meant to use.
        val output = MemoryMonitorTool(ApplicationProvider.getApplicationContext()).invoke(JSONObject())

        assertTrue("expected a report, got: $output", output.contains("TOP MEMORY CONSUMERS"))
        assertTrue("should have come from the bridge: $output", output.contains("privileged shell"))
        assertTrue("and should say what it measured: $output", output.contains("RSS"))
    }

    @Test
    fun killProcessTakesThePrivilegedPathWhenABackendExists() = runBlocking {
        // Nothing is force-stopped here: ShellGate.modify cannot execute, and this
        // instrumentation run is not foregrounded, so the proposal is fail-safe dropped.
        // What is under test is which of the two branches the tool chose — the weak
        // killBackgroundProcesses path stays reachable and would look almost identical.
        val reply = KillProcessTool(ApplicationProvider.getApplicationContext())
            .invoke(JSONObject("""{"package_name":"com.example.absent.mobileclaw"}"""))

        assertTrue("expected the force-stop branch, got: $reply", reply.contains("force-stop"))
        assertTrue("should not report unavailable: $reply", !reply.startsWith("[capability_unavailable"))
    }

    @Test
    fun theBridgeWillNotForceStopTheAppItself() = runBlocking {
        // The one target that would erase the record of the command that ran it:
        // ShellGate writes the audit row after exec returns, in a process this would
        // have killed.
        val shell = ShellRegistry.current()!!

        val result = shell.exec(listOf("am", "force-stop", "edu.pitt.isl.mobileclaw.notiftest"))

        assertEquals(PrivilegedService.REFUSED_EXIT, result.exitCode)
        assertTrue("expected a refusal, got: $result", result.stderr.contains("itself"))
    }

    @Test
    fun aFailingCommandReportsItsRealExitStatus() = runBlocking {
        val shell = ShellRegistry.current()!!
        val result = shell.exec(listOf("cat", "--", "/sdcard/definitely-not-here-9c38b60"))
        assertTrue("a missing file must not look like success", !result.ok)
        assertTrue("stderr should explain: ${result.stderr}", result.stderr.isNotBlank())
    }

    @Test
    fun writeAndReadBackRoundTrips() = runBlocking {
        // Proves content travels as a payload rather than an argument: the string below
        // would be several commands if it were ever pasted into a shell line.
        val shell = ShellRegistry.current()!!
        val path = "/sdcard/mobileclaw-live-test.txt"
        val content = "hello; rm -rf /sdcard/DCIM; echo \$(id) `whoami`\n"

        val wrote = shell.write(path, content)
        assertTrue("write failed: ${wrote.stderr}", wrote.ok)

        val read = shell.exec(listOf("cat", "--", path))
        assertTrue("read back failed: ${read.stderr}", read.ok)
        assertEquals(content, read.stdout)

        shell.exec(listOf("rm", "--", path))
        Unit // runBlocking returns its last expression, and a @Test must return void
    }
}
