package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

/** Pure admission rules, shared by preparation and the actual approval callback. */
internal object PhotoMmsPolicy {
    data class Phone(val contactId: String, val name: String, val number: String)

    sealed interface Recipient {
        data class Contact(val query: String) : Recipient
        data class Direct(val phone: Phone) : Recipient
    }

    fun recipient(contactName: String, phoneNumber: String): Recipient {
        val name = contactName.trim()
        val number = phoneNumber.trim()
        require(name.isNotEmpty() xor number.isNotEmpty()) {
            "Provide exactly one recipient: contact_name or phone_number."
        }
        // Older calls may have put the user's number into contact_name. Recognize
        // a complete number before attempting a display-name search in Contacts.
        if (number.isNotEmpty() || normalizeNumber(name) != null) {
            val normalized = normalizeNumber(number.ifEmpty { name })
                ?: throw IllegalArgumentException("Give one valid phone number (7 to 15 digits, optionally starting with +); extensions and multiple recipients are not supported.")
            return Recipient.Direct(Phone("", "Phone number", normalized))
        }
        require(name.length <= 100) { "Give the contact's name, up to 100 characters." }
        return Recipient.Contact(name)
    }

    fun normalizeNumber(number: String): String? = number.replace(Regex("[ ().-]"), "")
        .takeIf { Regex("\\+?[0-9]{7,15}").matches(it) }

    fun resolve(phones: List<Phone>): Phone {
        require(phones.isNotEmpty()) { "No contact with a phone number matches that name." }
        require(phones.map { it.contactId }.distinct().size == 1) {
            "More than one contact matches. Ask for a more specific contact name."
        }
        val normalized = phones.map {
            it.copy(number = normalizeNumber(it.number)
                ?: throw IllegalArgumentException("The contact has an unsupported phone number; check Contacts first."))
        }.distinctBy { it.number }
        require(normalized.size == 1) { "This contact has multiple phone numbers. Keep one intended number in Contacts before preparing this MMS." }
        return normalized.single()
    }

    fun refusal(foreground: Boolean, activeSession: String, proposalSession: String,
                selectionCurrent: Boolean, createdMs: Long, nowMs: Long): String? = when {
        !foreground || activeSession != proposalSession -> "Return to the conversation that prepared this photo before sending."
        !selectionCurrent -> "The selected photo changed. Open the intended photo and prepare the message again."
        nowMs < createdMs || nowMs - createdMs > APPROVAL_TTL_MS -> "This photo confirmation expired. Prepare a new message."
        else -> null
    }

    const val APPROVAL_TTL_MS = 5 * 60 * 1000L
}
