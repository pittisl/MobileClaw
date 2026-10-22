package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import android.provider.ContactsContract
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves a name (or partial name) to phone numbers and email addresses
 * so the agent can turn "Mom" or "Prof. Gao" into an address for compose_message /
 * send_sms, or label a notification sender. Read-only and ungated; requires
 * READ_CONTACTS. Output is not redacted per se (an address is the whole point),
 * but the tool returns nothing when the permission is missing.
 */
class LookupContactTool(private val context: Context) : Tool {

    override val name = "lookup_contact"
    override val description =
        "Look up a person's phone number(s) and email(s) by name (or partial name). " +
        "Use before send_sms or compose_message when you only know who, not their address."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Name or partial name to search for (e.g. \"Mom\", \"Gao\")." }
          },
          "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String = withContext(Dispatchers.IO) {
        val query = args.optString("query").trim()
        if (query.isBlank()) return@withContext "I need a name to look up."

        if (!Capability.READ_CONTACTS.isGranted(context)) {
            PermissionSignal.request(Capability.READ_CONTACTS)
            return@withContext Capability.unavailable(Capability.READ_CONTACTS)
        }

        val matches = queryContacts(query)
        if (matches.isEmpty()) return@withContext "No contact matches \"$query\"."

        matches.entries.take(5).joinToString("\n") { (name, info) ->
            val phones = info.phones.joinToString(", ").ifBlank { "—" }
            val emails = info.emails.joinToString(", ").ifBlank { "—" }
            "$name — phone: $phones · email: $emails"
        }
    }

    private class ContactInfo {
        val phones = linkedSetOf<String>()
        val emails = linkedSetOf<String>()
    }

    private fun queryContacts(query: String): Map<String, ContactInfo> {
        val results = LinkedHashMap<String, ContactInfo>()

        // Phone numbers matching the display name.
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val num = c.getString(numIdx) ?: continue
                results.getOrPut(name) { ContactInfo() }.phones.add(num.trim())
            }
        }

        // Email addresses matching the display name.
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            ),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val addr = c.getString(addrIdx) ?: continue
                results.getOrPut(name) { ContactInfo() }.emails.add(addr.trim())
            }
        }

        return results
    }
}
