package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Covers [ActionLogAdapter.formatTime]'s today/not-today split.
 *
 * Worth a test despite being a date format: retention keeps rows for 90 days, and the
 * failure mode is silent and misleading rather than visible. A bare `HH:mm:ss` on a
 * month-old row reads as something the agent did this afternoon — in the one screen
 * whose whole purpose is answering "what did it do, and when".
 *
 * Deliberately relative to the current clock rather than fixed instants: the rule is
 * "same calendar day as now", so pinning a date would only test the formatter.
 */
class ActionLogTimeTest {

    private val timeOnly = Regex("""^\d{2}:\d{2}:\d{2}$""")

    private fun todayAt(hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `an entry from today shows only a clock reading`() {
        val formatted = ActionLogAdapter.formatTime(System.currentTimeMillis())
        assertTrue("expected HH:mm:ss, got \"$formatted\"", timeOnly.matches(formatted))
    }

    @Test
    fun `the first second of today is still today`() {
        val formatted = ActionLogAdapter.formatTime(todayAt(0, 0, 1))
        assertTrue("expected HH:mm:ss, got \"$formatted\"", timeOnly.matches(formatted))
    }

    @Test
    fun `one second before midnight is not today, even though it is minutes ago`() {
        // The nearest possible not-today instant. A rule written as "within 24 hours"
        // instead of "same calendar day" passes every other case here and fails this one.
        val formatted = ActionLogAdapter.formatTime(todayAt(0, 0, 0) - 1_000)
        assertTrue("expected a date, got \"$formatted\"", !timeOnly.matches(formatted))
    }

    @Test
    fun `an entry from last month carries its date`() {
        val lastMonth = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.timeInMillis
        val formatted = ActionLogAdapter.formatTime(lastMonth)
        assertTrue("expected a date, got \"$formatted\"", !timeOnly.matches(formatted))
    }

    @Test
    fun `same day-of-year in a different year is not today`() {
        // isToday compares DAY_OF_YEAR, which repeats annually — without the YEAR
        // check this is the case that slips through.
        val lastYear = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis
        val formatted = ActionLogAdapter.formatTime(lastYear)
        assertTrue("expected a date, got \"$formatted\"", !timeOnly.matches(formatted))
    }
}
