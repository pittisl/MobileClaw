package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhotoMmsTest {
    @Test fun wireFormatMatchesIndependentAospComposerPhotoOnly() {
        assertArrayEquals(resourceHex("aosp-photo-only.hex"),
            PhotoMmsPdu.encode("+12025550198", resource("pixel.jpg"), "", "mc-fixture-1"))
    }

    @Test fun wireFormatMatchesIndependentAospComposerUtf8Caption() {
        assertArrayEquals(resourceHex("aosp-photo-caption.hex"),
            PhotoMmsPdu.encode("+12025550198", resource("pixel.jpg"), "Here is the dog 🐕.", "mc-fixture-1"))
    }

    @Test fun encoderRefusesAddressInjectionAndOversizedText() {
        for (number in listOf("+12025550198,+12025550199", "tel:+12025550198", "123;456", "12345", "+12025550198\u0000"))
            assertThrows(IllegalArgumentException::class.java) { PhotoMmsPdu.encode(number, byteArrayOf(1), "", "fixture") }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoMmsPdu.encode("+12025550198", byteArrayOf(1), "🐕".repeat(2001), "fixture")
        }
    }

    @Test fun largePartLengthBoundariesMatchIndependentAospComposer() {
        resource("aosp-size-boundaries.sha256").toString(Charsets.UTF_8).trim().lines().forEach { line ->
            val (size, expectedHash) = line.split(" ")
            val encoded = PhotoMmsPdu.encode("+12025550198", ByteArray(size.toInt()) { (it % 251).toByte() }, "", "mc-fixture-1")
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
            assertEquals("Part length $size", expectedHash, actual)
        }
    }

    @Test fun duplicatePhoneRowsResolveButDifferentContactsAndPhonesDoNot() {
        val amy = PhotoMmsPolicy.Phone("12", "Amy", "+1 (202) 555-0198")
        assertEquals("+12025550198", PhotoMmsPolicy.resolve(listOf(amy, amy.copy(number = "+12025550198"))).number)
        assertThrows(IllegalArgumentException::class.java) { PhotoMmsPolicy.resolve(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PhotoMmsPolicy.resolve(listOf(amy, amy.copy(contactId = "13"))) }
        assertThrows(IllegalArgumentException::class.java) { PhotoMmsPolicy.resolve(listOf(amy, amy.copy(number = "+12025550199"))) }
        assertThrows(IllegalArgumentException::class.java) { PhotoMmsPolicy.resolve(listOf(amy.copy(number = "+12025550198 ext 42"))) }
    }

    @Test fun explicitPhoneNumberDoesNotNeedAContactAndPreservesCountryCode() {
        val request = PhotoMmsPolicy.recipient("", "  +1 (202) 555-0198  ") as PhotoMmsPolicy.Recipient.Direct
        assertEquals("+12025550198", request.phone.number)
        assertEquals("", request.phone.contactId)
        assertEquals("Phone number", request.phone.name)
        assertEquals("2025550198", (PhotoMmsPolicy.recipient("", "202.555.0198") as PhotoMmsPolicy.Recipient.Direct).phone.number)
    }

    @Test fun aNumberInTheLegacyContactFieldBypassesNameLookup() {
        val request = PhotoMmsPolicy.recipient("+1 (202) 555-0198", "") as PhotoMmsPolicy.Recipient.Direct
        assertEquals("+12025550198", request.phone.number)
        assertEquals(PhotoMmsPolicy.Recipient.Contact("Amy"), PhotoMmsPolicy.recipient(" Amy ", ""))
    }

    @Test fun recipientMustBeUnambiguousAndCannotInjectOtherAddresses() {
        for ((name, number) in listOf("" to "", " " to " ", "Amy" to "+12025550198",
                "+12025550198" to "+12025550199", "A".repeat(101) to "")) {
            assertThrows(IllegalArgumentException::class.java) { PhotoMmsPolicy.recipient(name, number) }
        }
        for (number in listOf("Amy", "12345", "+1234567890123456", "tel:+12025550198", "+12025550198 ext 42",
                "+12025550198,+12025550199", "+12025550198;12025550199", "+12025550198/TYPE=PLMN",
                "+12025550198\u0000", "+12025550198\nBcc:12025550199")) {
            assertThrows("Accepted $number", IllegalArgumentException::class.java) { PhotoMmsPolicy.recipient("", number) }
        }
    }

    @Test fun foregroundSessionSelectionAndExpiryAreAllRequired() {
        assertNull(PhotoMmsPolicy.refusal(true, "a", "a", true, 1_000, 2_000))
        assertNotNull(PhotoMmsPolicy.refusal(false, "a", "a", true, 1_000, 2_000))
        assertNotNull(PhotoMmsPolicy.refusal(true, "b", "a", true, 1_000, 2_000))
        assertNotNull(PhotoMmsPolicy.refusal(true, "a", "a", false, 1_000, 2_000))
        assertNotNull(PhotoMmsPolicy.refusal(true, "a", "a", true, 2_000, 1_000))
        assertNotNull(PhotoMmsPolicy.refusal(true, "a", "a", true, 1_000, 301_001))
    }

    @Test fun preparingCannotCallTransportAndApprovalOnlyCallsItOnce() = runBlocking {
        var calls = 0
        val proposal = PhotoMmsProposal.create("Photo to Amy", "session", "/private/photo.jpg", {}, {}, { calls++; "submitted" })
        assertFalse(proposal.allowFullAutomation)
        assertEquals("session", proposal.sessionId)
        assertEquals("/private/photo.jpg", proposal.imagePreviewPath)
        assertEquals(0, calls)
        assertEquals("submitted", proposal.execute())
        assertEquals(1, calls)
        assertTrue(proposal.execute().contains("already used"))
        assertEquals(1, calls)
    }

    @Test fun refusalAfterPreparationNeverCallsEvenTheFakeTransport() = runBlocking {
        var calls = 0
        var discards = 0
        val proposal = PhotoMmsProposal.create("Photo to Amy", "session", "/private/photo.jpg", { discards++ },
            { error("Selected image changed") }, { calls++; "submitted" })
        assertTrue(proposal.execute().contains("Selected image changed"))
        assertEquals(0, calls)
        assertEquals(1, discards)
    }

    @Test fun declineConsumesPermissionEvenWhenExecuteLambdaIsRetained() = runBlocking {
        var calls = 0
        var discards = 0
        val proposal = PhotoMmsProposal.create("Photo to Amy", "session", "/private/photo.jpg", { discards++ }, {},
            { calls++; "submitted" })
        val retainedExecute = proposal.execute
        proposal.onDiscard!!.invoke()
        proposal.onDiscard!!.invoke()
        assertTrue(retainedExecute().contains("already used"))
        assertEquals(0, calls)
        assertEquals(1, discards)
    }

    @Test fun carrierReceiptDoesNotClaimRecipientDelivery() {
        val success = PhotoMmsReceipt.render("Amy", -1)
        assertTrue(success.contains("carrier submission"))
        assertTrue(success.contains("recipient's phone is not confirmed"))
        assertFalse(PhotoMmsReceipt.render("Amy", 2).startsWith("MMS sent"))
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/mms/$name")!!.use { it.readBytes() }
    private fun resourceHex(name: String): ByteArray = resource(name).toString(Charsets.UTF_8).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
