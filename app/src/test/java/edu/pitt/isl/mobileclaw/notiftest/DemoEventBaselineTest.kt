package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.*
import org.junit.Test

class DemoEventBaselineTest {
    @Test fun oldShadeIsIgnoredButANewSmsOnTheSameKeyIsAdmitted() {
        val b = DemoEventBaseline()
        b.reset(listOf("thread" to 100L))
        assertTrue(b.shouldIgnore("thread", 100, true))
        assertTrue(b.shouldIgnore("thread", 100, true))
        assertFalse(b.shouldIgnore("thread", 101, true))
        assertFalse(b.shouldIgnore("thread", 101, true))
        assertFalse(b.shouldIgnore("another thread", 100, true))
    }

    @Test fun removingAnOldNotificationDoesNotRecaptureItAndARepostIsNews() {
        val b = DemoEventBaseline()
        b.reset(listOf("thread" to 100L))
        assertTrue(b.shouldIgnore("thread", 100, false))
        assertFalse(b.shouldIgnore("thread", 100, true))
    }

    @Test fun aSecondResetReplacesTheBaseline() {
        val b = DemoEventBaseline()
        b.reset(listOf("old" to 100L))
        b.reset(listOf("new" to 200L))
        assertFalse(b.shouldIgnore("old", 100, true))
        assertTrue(b.shouldIgnore("new", 200, true))
    }
}
