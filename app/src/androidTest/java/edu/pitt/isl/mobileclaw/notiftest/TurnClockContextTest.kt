package edu.pitt.isl.mobileclaw.notiftest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

/** Verifies the real foreground context provider; never clears chat or calls a model. */
@RunWith(AndroidJUnit4::class)
class TurnClockContextTest {
    @Test fun foregroundContextIncludesCurrentYearOffsetAndTimezone() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var provider: () -> String
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MainViewModel(app) { context -> provider = context; FakeAiClient { done("unused") } }
        }
        val before = ZonedDateTime.now()
        val firstLine = provider().lineSequence().first()
        assertTrue("Foreground context lost the device clock", firstLine.startsWith("Current device time: "))
        val supplied = ZonedDateTime.parse(firstLine.removePrefix("Current device time: "))
        val after = ZonedDateTime.now()
        assertEquals(before.year, supplied.year)
        assertEquals(before.zone, supplied.zone)
        assertEquals(before.offset, supplied.offset)
        assertEquals("The clock must survive the production redaction boundary", firstLine, ReasoningModule.redact(firstLine))
        assertFalse(supplied.toInstant().isBefore(before.minusSeconds(1).toInstant()))
        assertFalse(supplied.toInstant().isAfter(after.toInstant()))
    }
}
