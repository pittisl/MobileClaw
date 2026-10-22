package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** English source-selection policy, not evidence extraction or model-capability tests. */
class NotificationContextRequirementsTest {
    @Test fun `explicit document questions require file search`() {
        for (source in listOf("PDF", "document", "receipt", "invoice", "ticket", "booking code",
            "booking confirmation", "booking record", "confirmation code", "confirmation document")) {
            assertEquals(source, setOf("search_files"),
                NotificationContextRequirements.readersFor("What does the $source say?"))
        }
    }

    @Test fun `explicit personal scheduling sources require calendar`() {
        for (source in listOf("calendar", "availability", "free slot", "free time", "schedule", "appointment", "meeting")) {
            assertEquals(source, setOf("read_calendar"),
                NotificationContextRequirements.readersFor("Please check my $source."))
        }
    }

    @Test fun `hotel question has independent document and personal arrival obligations`() {
        assertEquals(setOf("search_files", "read_calendar"), NotificationContextRequirements.readersFor(
            "Cedar House PDF booking code? Is our Oct 25 arrival before check-in?"))
    }

    @Test fun `invoice question works without any hotel vocabulary`() {
        assertEquals(setOf("search_files"), NotificationContextRequirements.readersFor(
            "What are the reference and due date on the workshop invoice?"))
        assertEquals(setOf("search_files", "read_calendar"), NotificationContextRequirements.readersFor(
            "What is the workshop ticket code, and is our planned arrival before the doors close?"))
    }

    @Test fun `personal travel comparisons require calendar without mentioning calendar`() {
        for (text in listOf(
            "Will my arrival be before the doors close?",
            "Do we arrive after the workshop starts?",
            "Am I arriving earlier than the opening time?",
            "Is our planned arrival later than the deadline?",
            "Does my flight overlap the performance?",
            "Will our trip conflict with the inspection?",
        )) {
            assertEquals(text, setOf("read_calendar"), NotificationContextRequirements.readersFor(text))
        }
    }

    @Test fun `a personal plan alone is not a comparison obligation`() {
        assertTrue(NotificationContextRequirements.readersFor("Enjoy our trip!").isEmpty())
        assertTrue(NotificationContextRequirements.readersFor("We plan to arrive.").isEmpty())
    }

    @Test fun `a general timing comparison without personal plans does not require calendar`() {
        assertTrue(NotificationContextRequirements.readersFor("The doors open before the performance.").isEmpty())
        assertEquals(setOf("search_files"), NotificationContextRequirements.readersFor(
            "Does the invoice say payment is due before the workshop?"))
    }

    @Test fun `ordinary unrelated messages require neither source`() {
        for (text in listOf("", "Thanks!", "Dinner was great.", "The package was delivered.", "Can you call me?")) {
            assertTrue(text, NotificationContextRequirements.readersFor(text).isEmpty())
        }
    }

    @Test fun `case and punctuation do not hide explicit sources`() {
        assertEquals(setOf("search_files", "read_calendar"), NotificationContextRequirements.readersFor(
            "PDF? CALENDAR!"))
    }

    @Test fun `word fragments do not create unrelated source requirements`() {
        assertTrue(NotificationContextRequirements.readersFor(
            "The documentary and ticketing demonstration are ready.").isEmpty())
    }

    @Test fun `combined title and multiline body retain both source requirements`() {
        val title = "PDF booking code"
        val body = "Is our\nOct 25\narrival before check-in?"
        assertEquals(setOf("search_files", "read_calendar"),
            NotificationContextRequirements.readersFor("$title\n$body"))
        assertEquals(setOf("search_files"), NotificationContextRequirements.readersFor(
            "Invoice\nWhat is its reference number?"))
    }

    @Test fun `split phrases and personal plans survive mixed whitespace`() {
        assertEquals(setOf("search_files", "read_calendar"), NotificationContextRequirements.readersFor(
            "Where is the booking\r\n\tconfirmation? Is our\t\nplanned arrival later than the opening time?"))
        assertEquals(setOf("read_calendar"), NotificationContextRequirements.readersFor(
            "Could you check my free\n\t  slot?"))
    }
}
