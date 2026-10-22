package edu.pitt.isl.mobileclaw.notiftest.skills.search

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class CaptureDateTest {
    private val newYork = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(Instant.parse("2026-10-15T16:00:00Z"), newYork)

    @Test fun `EXIF offset is authoritative across device zones`() {
        val value = CaptureDate.fromExif("2026:10:14 09:15:00", "-04:00", ZoneId.of("Asia/Tokyo"))!!
        assertEquals(Instant.parse("2026-10-14T13:15:00Z").toEpochMilli(), value.atMs)
        assertEquals("EXIF -04:00", value.source)
    }

    @Test fun `offsetless EXIF records the timezone assumption`() {
        val value = CaptureDate.fromExif("2026:10:14 09:15:00", null, newYork)!!
        assertEquals(Instant.parse("2026-10-14T13:15:00Z").toEpochMilli(), value.atMs)
        assertTrue(value.source.contains("assumed zone America/New_York"))
    }

    @Test fun `invalid or absent EXIF remains unknown`() {
        assertNull(CaptureDate.fromExif(null, null, newYork))
        assertNull(CaptureDate.fromExif("0000:00:00 00:00:00", null, newYork))
        assertNull(CaptureDate.fromExif("2026:02:30 09:15:00", "-04:00", newYork))
        assertNull(CaptureDate.fromExif("2026:10:14 09:15:00", "corrupt", newYork))
        assertNull(CaptureDate.fromMediaStore(0))
        assertNull(CaptureDate.fromMediaStore(null))
        assertEquals("MediaStore DATE_TAKEN", CaptureDate.fromMediaStore(1234)!!.source)
    }

    @Test fun `yesterday uses the device calendar day and excludes unknown dates`() {
        val filter = CaptureDate.filter(on = "yesterday", zone = newYork, clock = clock)!!
        assertEquals(Instant.parse("2026-10-14T04:00:00Z").toEpochMilli(), filter.afterMs)
        assertEquals(Instant.parse("2026-10-15T04:00:00Z").toEpochMilli(), filter.beforeMs)
        assertTrue(filter.includes(filter.afterMs))
        assertFalse(filter.includes(filter.afterMs!! - 1))
        assertFalse(filter.includes(filter.beforeMs))
        assertFalse(filter.includes(null))
    }

    @Test fun `relative days resolve in the requested zone rather than UTC`() {
        val shortlyAfterMidnightUtc = Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneId.of("UTC"))
        val filter = CaptureDate.filter(on = "today", zone = newYork, clock = shortlyAfterMidnightUtc)!!
        assertEquals(Instant.parse("2026-10-14T04:00:00Z").toEpochMilli(), filter.afterMs)
    }

    @Test fun `DST date boundaries use calendar arithmetic`() {
        val spring = CaptureDate.filter(on = "2026-03-08", zone = newYork)!!
        val fall = CaptureDate.filter(on = "2026-11-01", zone = newYork)!!
        assertEquals(23 * 3_600_000L, spring.beforeMs!! - spring.afterMs!!)
        assertEquals(25 * 3_600_000L, fall.beforeMs!! - fall.afterMs!!)
    }

    @Test fun `range boundaries are inclusive then exclusive`() {
        val filter = CaptureDate.filter(after = "2026-10-13", before = "2026-10-15", zone = newYork)!!
        assertTrue(filter.includes(Instant.parse("2026-10-14T20:00:00Z").toEpochMilli()))
        assertFalse(filter.includes(Instant.parse("2026-10-15T04:00:00Z").toEpochMilli()))
        assertNull(CaptureDate.filter())
    }

    @Test fun `invalid and contradictory date filters fail closed`() {
        for (run in listOf<() -> Unit>(
            { CaptureDate.filter(on = "2026-02-30") },
            { CaptureDate.filter(on = "10/14/2026") },
            { CaptureDate.filter(on = "  ") },
            { CaptureDate.filter(on = "yesterday", after = "2026-10-13") },
            { CaptureDate.filter(after = "2026-10-15", before = "2026-10-14") },
            { CaptureDate.filter(after = "2026-10-14", before = "2026-10-14") },
        )) {
            try { run(); fail("Invalid filter was accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `old index versions require a metadata rebuild`() {
        val built = IndexState.Snapshot(1, 10, 0, 0, 1, null)
        assertFalse(built.needsBuild)
        assertTrue(built.copy(schemaVersion = 4).needsBuild)
        assertTrue(built.copy(schemaVersion = 0).needsBuild)
    }
}
