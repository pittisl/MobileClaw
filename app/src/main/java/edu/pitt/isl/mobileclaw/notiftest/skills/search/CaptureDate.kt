package edu.pitt.isl.mobileclaw.notiftest.skills.search

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Capture time is separate from file modification time; a copied photo keeps its date. */
object CaptureDate {
    data class Value(val atMs: Long, val source: String)

    private val exifFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    /**
     * OffsetTimeOriginal is authoritative when present. Older cameras omit it; then the
     * current device zone is an explicit assumption, retained in [Value.source]. Never
     * use EXIF DateTime (editing time), a filename, or mtime as a capture-time substitute.
     * Invalid metadata is unknown so the caller may try MediaStore's DATE_TAKEN.
     */
    fun fromExif(original: String?, offset: String?, zone: ZoneId): Value? {
        if (original.isNullOrBlank()) return null
        return try {
            val local = LocalDateTime.parse(original.trim(), exifFormat)
            if (offset.isNullOrBlank()) {
                Value(local.atZone(zone).toInstant().toEpochMilli(), "EXIF; assumed zone ${zone.id}")
            } else {
                // EXIF defines a signed HH:MM offset, not arbitrary ZoneOffset shorthand.
                if (!offset.trim().matches(Regex("[+-]\\d{2}:\\d{2}"))) return null
                Value(local.toInstant(ZoneOffset.of(offset.trim())).toEpochMilli(), "EXIF ${offset.trim()}")
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fromMediaStore(dateTakenMs: Long?): Value? =
        dateTakenMs?.takeIf { it > 0 }?.let { Value(it, "MediaStore DATE_TAKEN") }

    /** Local calendar days, converted once to an instant interval; DST days need not be 24 h. */
    data class Filter(val afterMs: Long?, val beforeMs: Long?, val description: String) {
        fun includes(capturedAtMs: Long?): Boolean = capturedAtMs != null &&
            (afterMs == null || capturedAtMs >= afterMs) &&
            (beforeMs == null || capturedAtMs < beforeMs)
    }

    /** Reject conflicting/invalid parameters instead of silently dropping a requested date. */
    fun filter(
        on: String? = null,
        after: String? = null,
        before: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(zone),
    ): Filter? {
        fun supplied(value: String?): String? = value?.trim()?.also {
            require(it.isNotEmpty()) { "Capture dates cannot be blank; omit an unused parameter." }
        }
        val day = supplied(on)
        val start = supplied(after)
        val end = supplied(before)
        require(day == null || (start == null && end == null)) {
            "Use captured_on or captured_after/captured_before, not both."
        }
        if (day == null && start == null && end == null) return null
        fun date(value: String): LocalDate {
            require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Capture dates must use yyyy-MM-dd." }
            return try { LocalDate.parse(value) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid capture date: $value; use yyyy-MM-dd.")
            }
        }
        val from: LocalDate?
        val until: LocalDate?
        if (day != null) {
            from = when (day.lowercase(Locale.ROOT)) {
                "today" -> LocalDate.now(clock.withZone(zone))
                "yesterday" -> LocalDate.now(clock.withZone(zone)).minusDays(1)
                else -> date(day)
            }
            until = from.plusDays(1)
        } else {
            from = start?.let(::date)
            until = end?.let(::date)
        }
        require(from == null || until == null || from < until) {
            "captured_before must be later than captured_after."
        }
        return Filter(
            from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            until?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            "Capture date: ${from ?: "any earlier date"} (inclusive) to ${until ?: "any later date"} " +
                "(exclusive), ${zone.id}; photos with unknown capture dates are excluded.",
        )
    }
}
