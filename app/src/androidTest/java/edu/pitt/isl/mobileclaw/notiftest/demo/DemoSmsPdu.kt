package edu.pitt.isl.mobileclaw.notiftest.demo

import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.ZoneOffset

/** Test-only class-1 SMS-DELIVER encoder shared by the offline and live probes. */
internal object DemoSmsPdu {
    fun deliverPdu(sender: String, text: String): ByteArray {
        val digits = sender.removePrefix("+")
        val payload = text.toByteArray(Charsets.UTF_16BE)
        require(payload.size <= 140) { "Single-part fixture exceeds SMS payload" }
        fun swapped(value: Int) = (value % 10 shl 4) or (value / 10)
        return ByteArrayOutputStream().apply {
            write(0) // Use the receiving subscription's service centre.
            write(0) // SMS-DELIVER, no user-data header.
            write(digits.length); write(0x91)
            digits.chunked(2).forEach { pair ->
                write(pair[0].digitToInt() or ((pair.getOrNull(1)?.digitToInt() ?: 15) shl 4))
            }
            // injectSmsPdu only admits class 1; plain UCS-2 (0x08) is rejected even
            // though createFromPdu can parse it. 0x19 = UCS-2 + class-present + class 1.
            write(0); write(0x19)
            val time = ZonedDateTime.now(ZoneOffset.UTC)
            listOf(time.year % 100, time.monthValue, time.dayOfMonth, time.hour,
                time.minute, time.second, 0).forEach { write(swapped(it)) }
            write(payload.size); write(payload)
        }.toByteArray()
    }
}
