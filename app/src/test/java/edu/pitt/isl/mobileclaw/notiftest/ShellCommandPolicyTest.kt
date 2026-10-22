package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the **privileged process** will agree to run.
 *
 * These matter more than their size suggests. Every other check in this codebase
 * protects the side that a prompt injection is talking to; this one protects the side
 * holding uid 2000, and it is the only check that still applies if the app's own
 * vetting is bypassed, buggy, or simply forgotten by a tool added later. It runs on
 * the JVM with no device, no Shizuku and no privilege, which is the entire reason the
 * policy is a separate pure object rather than inline in [PrivilegedService].
 */
class ShellCommandPolicyTest {

    // ---- the allowlist ------------------------------------------------------

    @Test
    fun `the commands file_system actually offers are allowed`() {
        listOf(
            listOf("ls", "--", "/sdcard"),
            listOf("cat", "--", "/sdcard/notes.txt"),
            listOf("stat", "--", "/sdcard"),
            listOf("find", "/sdcard", "-name", "*.txt"),
            listOf("mkdir", "-p", "--", "/sdcard/new"),
            listOf("rm", "-rf", "--", "/sdcard/tmp"),
            listOf("mv", "--", "/sdcard/a", "/sdcard/b"),
            listOf("cp", "-r", "--", "/sdcard/a", "/sdcard/b"),
            listOf("ps", "-A", "-o", "RSS,NAME")
        ).forEach { argv ->
            assertNull("$argv should be allowed", ShellCommandPolicy.reject(argv))
        }
    }

    @Test
    fun `a shell is not an allowed program`() {
        // The one that matters most. Letting `sh -c` through would re-open the
        // command-string hole from the privileged side, after the privileged-command policy closed it on the
        // app side — argv framing means nothing if argv[0] can interpret its arguments.
        listOf("sh", "bash", "toybox", "busybox", "toolbox").forEach { program ->
            val reason = ShellCommandPolicy.reject(listOf(program, "-c", "rm -rf /sdcard"))
            assertNotNull("$program must be refused", reason)
            assertTrue(reason!!, reason.contains("not an allowed program"))
        }
    }

    @Test
    fun `commands nobody thought to forbid are refused by default`() {
        // The list is an allowlist, not a denylist, and this is what that buys: none of
        // these were reasoned about individually, and all of them are refused anyway.
        listOf(
            listOf("chmod", "777", "/sdcard"),
            listOf("dd", "if=/dev/zero", "of=/sdcard/x"),
            listOf("pm", "uninstall", "com.example"),
            listOf("settings", "put", "global", "adb_enabled", "1"),
            listOf("curl", "https://example.com")
        ).forEach { argv ->
            assertNotNull("$argv must be refused", ShellCommandPolicy.reject(argv))
        }
    }

    @Test
    fun `an absolute or relative path cannot smuggle a different program`() {
        // Matching on the whole string would let "/system/bin/sh" past a check that
        // only knows "sh", so the comparison is on the basename.
        assertNotNull(ShellCommandPolicy.reject(listOf("/system/bin/sh", "-c", "id")))
        assertNotNull(ShellCommandPolicy.reject(listOf("../../system/bin/sh", "-c", "id")))
        // …and the same rule must not break the legitimate absolute form.
        assertNull(ShellCommandPolicy.reject(listOf("/system/bin/ls", "--", "/sdcard")))
    }

    @Test
    fun `a trailing slash leaves no program name`() {
        assertNotNull(ShellCommandPolicy.reject(listOf("/system/bin/", "arg")))
    }

    // ---- am: where the basename is not the unit of authority --------------------

    @Test
    fun `am is allowed for force-stop and nothing else it can do`() {
        // The one form kill_process needs.
        assertNull(ShellCommandPolicy.reject(listOf("am", "force-stop", "com.example.app")))

        // …and the rest of the program, which came free with the basename and is why
        // this program needs an argument rule at all. `am start` launches any component
        // with attacker-chosen extras and URIs; `am broadcast` forges a broadcast to any
        // receiver on the device. Both would have been allowed by a basename allowlist.
        listOf(
            listOf("am", "start", "-a", "android.intent.action.VIEW", "-d", "https://evil.example"),
            listOf("am", "broadcast", "-a", "com.example.PAY"),
            listOf("am", "instrument", "-w", "com.example/.Runner"),
            listOf("am", "monitor")
        ).forEach { argv ->
            val reason = ShellCommandPolicy.reject(argv)
            assertNotNull("$argv must be refused", reason)
            assertTrue(reason!!, reason.contains("force-stop"))
        }
    }

    @Test
    fun `force-stop takes exactly one package and no extra arguments`() {
        // Trailing arguments are the way a rule that only checks argv[1] gets walked
        // past, so the length is pinned rather than the prefix matched.
        assertNotNull(ShellCommandPolicy.reject(listOf("am", "force-stop")))
        assertNotNull(ShellCommandPolicy.reject(listOf("am", "force-stop", "com.a", "com.b")))
        assertNotNull(ShellCommandPolicy.reject(listOf("am", "force-stop", "--user", "0", "com.a")))
    }

    @Test
    fun `a force-stop target must look like a package name`() {
        listOf("", "-a", "not a package", "no-dots", "../etc", "com..example", "1com.example")
            .forEach { target ->
                assertNotNull(
                    "\"$target\" must be refused",
                    ShellCommandPolicy.reject(listOf("am", "force-stop", target))
                )
            }
    }

    @Test
    fun `MobileClaw will not force-stop itself`() {
        // Not squeamishness: ShellGate writes the audit row after exec returns, in the
        // app process — so this is the one command that would erase the record of
        // itself, and the one target that takes out the confirm dialog with it.
        val reason = ShellCommandPolicy.reject(
            listOf("am", "force-stop", "edu.pitt.isl.mobileclaw.notiftest")
        )
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("itself"))
    }

    // ---- set-standby-bucket (exactly 4 elements) ----

    @Test
    fun `am is allowed to restrict a package`() {
        assertNull(
            ShellCommandPolicy.reject(
                listOf("am", "set-standby-bucket", "com.example.app", "restricted")
            )
        )
    }

    @Test
    fun `only the restricted bucket may be set`() {
        // The whole reason the last element is pinned rather than merely counted. This
        // subcommand's other values *raise* an app's standing — "active" would turn a
        // throttling tool into one that grants background freedom to anything the model
        // can be talked into naming, which is the opposite of what it exists for.
        listOf("active", "working_set", "frequent", "rare", "never", "10", "")
            .forEach { bucket ->
                assertNotNull(
                    "bucket \"$bucket\" must be refused",
                    ShellCommandPolicy.reject(
                        listOf("am", "set-standby-bucket", "com.example.app", bucket)
                    )
                )
            }
    }

    @Test
    fun `set-standby-bucket takes exactly a package and a bucket`() {
        // Same pinning argument as force-stop: a rule that only checks argv[1] is walked
        // past with trailing arguments.
        assertNotNull(ShellCommandPolicy.reject(listOf("am", "set-standby-bucket")))
        assertNotNull(ShellCommandPolicy.reject(listOf("am", "set-standby-bucket", "com.a")))
        assertNotNull(
            ShellCommandPolicy.reject(
                listOf("am", "set-standby-bucket", "com.a", "restricted", "extra")
            )
        )
        assertNotNull(
            ShellCommandPolicy.reject(
                listOf("am", "set-standby-bucket", "--user", "0", "com.a", "restricted")
            )
        )
    }

    @Test
    fun `a restrict target must look like a package name`() {
        listOf("", "-a", "not a package", "no-dots", "../etc", "com..example", "1com.example")
            .forEach { target ->
                assertNotNull(
                    "\"$target\" must be refused",
                    ShellCommandPolicy.reject(
                        listOf("am", "set-standby-bucket", target, "restricted")
                    )
                )
            }
    }

    @Test
    fun `MobileClaw will not restrict itself`() {
        // Restricting ourselves throttles the background triage loop — the agent
        // disabling itself. A hostile notification can ask for that in one sentence, and
        // the result would look like the app had simply stopped working.
        val reason = ShellCommandPolicy.reject(
            listOf("am", "set-standby-bucket", "edu.pitt.isl.mobileclaw.notiftest", "restricted")
        )
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("itself"))
    }

    // ---- the vector itself ---------------------------------------------------

    // ---- cmd app_function: one nine-element vector, nothing else cmd can do ----

    private fun callVector(pkg: String = "com.example.chatapp", id: String = "com.example.chatapp.appfunctions.ChatAppFunctionService#searchContacts", json: String = """{"query":"alex","contactType":"ANY"}""") =
        listOf("cmd", "app_function", "execute-app-function", "--package", pkg, "--function", id, "--parameters", json)

    @Test
    fun `cmd is allowed for execute-app-function with the exact vector and nothing else`() {
        assertNull(ShellCommandPolicy.reject(callVector()))
        // Every other service behind `cmd`, and the other app_function subcommand.
        assertNotNull(ShellCommandPolicy.reject(listOf("cmd", "package", "list", "packages")))
        assertNotNull(ShellCommandPolicy.reject(listOf("cmd", "notification", "post", "-t", "x", "tag", "body")))
        assertNotNull(ShellCommandPolicy.reject(listOf("cmd", "app_function", "set-enabled", "--package", "com.example.chatapp", "--function", "a.B#c", "--state", "enable")))
        // Optional arguments are refused, not tolerated: --user would run it as somebody else.
        assertNotNull(ShellCommandPolicy.reject(callVector() + listOf("--user", "10")))
        assertNotNull(ShellCommandPolicy.reject(callVector().dropLast(2)))
    }

    @Test
    fun `execute-app-function vets the package, the identifier and the parameters shape`() {
        assertNotNull(ShellCommandPolicy.reject(callVector(pkg = "not a package")))
        assertNotNull(ShellCommandPolicy.reject(callVector(id = "searchContacts")))
        assertNotNull(ShellCommandPolicy.reject(callVector(id = "com.example.Foo#bar; rm -rf /")))
        assertNotNull(ShellCommandPolicy.reject(callVector(json = "[1,2]")))
        assertNotNull(ShellCommandPolicy.reject(callVector(json = "query=alex")))
        assertNull("a nested object is still one JSON object", ShellCommandPolicy.reject(callVector(json = """{"a":{"b":[1,2]}}""")))
    }

    @Test
    fun `an empty command or argument is refused`() {
        assertNotNull(ShellCommandPolicy.reject(emptyList()))
        assertNotNull(ShellCommandPolicy.reject(listOf("ls", "", "/sdcard")))
    }

    @Test
    fun `a NUL is refused but a space is not`() {
        // NUL truncates the argument inside execve, so the command that runs would
        // differ from the one that was vetted. A space is just a character in a path,
        // and refusing it would mean argv framing had failed at its one job.
        assertNotNull(ShellCommandPolicy.reject(listOf("cat", "--", "/sdcard/a\u0000b")))
        assertNull(ShellCommandPolicy.reject(listOf("cat", "--", "/sdcard/My Documents/a b.txt")))
        assertNull(ShellCommandPolicy.reject(listOf("cat", "--", "/sdcard/weird';\$(id)`x`.txt")))
    }

    // ---- output capping ------------------------------------------------------

    @Test
    fun `output under the cap is untouched`() {
        assertEquals("file-a\nfile-b", ShellCommandPolicy.cap("file-a\nfile-b"))
    }

    @Test
    fun `oversized output is truncated and says so`() {
        // Binder shares a ~1 MB transaction buffer per process; returning an unbounded
        // `cat` of a video would throw TransactionTooLargeException rather than return
        // anything. Truncating silently would be worse than the cap, though — the model
        // would read a partial listing as a complete one.
        val huge = "x".repeat(ShellCommandPolicy.MAX_OUTPUT_BYTES * 2)

        val capped = ShellCommandPolicy.cap(huge)

        assertTrue("must actually be smaller", capped.length < huge.length)
        assertTrue("and must admit it: ${capped.takeLast(80)}", capped.contains("truncated"))
        assertTrue("reporting the real size", capped.contains("${huge.length} total"))
    }

    @Test
    fun `output exactly at the cap is not truncated`() {
        val exact = "x".repeat(ShellCommandPolicy.MAX_OUTPUT_BYTES)
        assertEquals(exact, ShellCommandPolicy.cap(exact))
    }
}
