package edu.pitt.isl.mobileclaw.notiftest

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed manifest/XML policy, which JVM HTTP tests cannot see. */
@RunWith(AndroidJUnit4::class)
class LlmNetworkPolicyTest {
    @Test fun cleartextIsAllowedOnlyForTheExplicitLocalAddresses() {
        val policy = NetworkSecurityPolicy.getInstance()
        for (host in listOf("localhost", "127.0.0.1", "::1", "10.0.2.2")) {
            assertTrue("Local HTTP denied for $host", policy.isCleartextTrafficPermitted(host))
        }
        for (host in listOf("api.openai.com", "192.0.2.1", "127.0.0.2", "example.localhost")) {
            assertFalse("Unexpected cleartext permission for $host", policy.isCleartextTrafficPermitted(host))
        }
    }
}
