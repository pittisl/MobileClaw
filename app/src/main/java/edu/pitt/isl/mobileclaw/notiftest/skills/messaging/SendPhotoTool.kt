package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone as ContactPhone
import android.telephony.SmsManager
import edu.pitt.isl.mobileclaw.notiftest.AppForeground
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.ChatStore
import edu.pitt.isl.mobileclaw.notiftest.PendingAction
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.PhotoSelectionStore
import edu.pitt.isl.mobileclaw.notiftest.PhotoTurn
import edu.pitt.isl.mobileclaw.notiftest.Redaction
import edu.pitt.isl.mobileclaw.notiftest.Tool
import edu.pitt.isl.mobileclaw.notiftest.ToolInvocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** A contact or explicit number supplies the recipient; the user selects the attachment. */
class SendPhotoTool(private val context: Context) : Tool {
    override val name = "send_photo"
    override val requiresConfirmationReceipt = true
    override val description =
        "Prepare an MMS with the photo the user opened and selected in this conversation. " +
            "Use for 'send that pic to Amy's phone' or 'send that pic to +12025550198'. " +
            "Provide exactly one of contact_name or phone_number. For a number supplied by the user, " +
            "use phone_number directly; it does not need to be saved in Contacts. Never invent a number. " +
            "You cannot choose a photo path: the app uses the user's selected photo. " +
            "For a name, the app resolves contact_name against Contacts. " +
            "Use lookup_contact to clarify an ambiguous name. If no photo is selected, ask the user to open " +
            "one of the photo search results. The app shows the exact image and recipient phone number and " +
            "always asks the user to confirm before sending. A prepared message is not sent. " +
            "Omit body unless the user wants a caption."
    override val parameters = JSONObject("""
        {"type":"object","properties":{
          "contact_name":{"type":"string","description":"Contact name requested by the user, e.g. Amy. Omit when using phone_number."},
          "phone_number":{"type":"string","description":"Recipient number supplied by the user, e.g. +12025550198. Spaces, parentheses, dots and hyphens are accepted. Omit when using contact_name."},
          "body":{"type":"string","description":"Optional short caption. Omit for photo only."}
        },"additionalProperties":false}
    """.trimIndent())

    override suspend fun invoke(args: JSONObject): String = invokeWithReceipt(args).text

    override suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation {
        val turn = coroutineContext[PhotoTurn]
            ?: return ToolInvocation.NotExecuted("Photo sharing requires a user-initiated conversation with a selected photo.")
        val photo = turn.selectedPhoto
            ?: return ToolInvocation.NotExecuted("Open the intended photo in MobileClaw first, then ask me to send that photo.")
        if (args.keys().asSequence().any { it !in setOf("contact_name", "phone_number", "body") })
            return ToolInvocation.NotExecuted("Only contact_name or phone_number and optional body are accepted; photo paths cannot be supplied by the model.")
        val request = try {
            PhotoMmsPolicy.recipient(args.optString("contact_name"), args.optString("phone_number"))
        } catch (error: IllegalArgumentException) {
            return ToolInvocation.NotExecuted(error.message.orEmpty())
        }
        val needsContacts = request is PhotoMmsPolicy.Recipient.Contact
        val body = args.optString("body")
        if (body.toByteArray(Charsets.UTF_8).size > 8_000)
            return ToolInvocation.NotExecuted("The photo caption is too long; use at most 8000 UTF-8 bytes.")
        refusal(turn, System.currentTimeMillis())?.let { return ToolInvocation.NotExecuted(it) }
        missingPermission(needsContacts)?.let { return ToolInvocation.NotExecuted(it) }
        return withContext(Dispatchers.IO) {
            var draft: PhotoMmsFiles.Draft? = null
            try {
                val recipient = when (request) {
                    is PhotoMmsPolicy.Recipient.Contact -> resolveContact(request.query)
                    is PhotoMmsPolicy.Recipient.Direct -> request.phone
                }
                val subscription = PhotoMmsTransport.defaultSubscription()
                val config = PhotoMmsTransport.manager(context, subscription).carrierConfigValues
                require(config.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, false)) { "MMS is not enabled in this SIM's carrier configuration." }
                val prepared = PhotoMmsFiles.prepare(context, photo, recipient, turn.sessionId, body, subscription, config)
                draft = prepared
                val description = "Send photo by MMS?\n${recipient.name} · ${recipient.number}\n" +
                    "${photo.name} · ${prepared.photo.length() / 1024} KB JPEG" +
                    (if (body.isNotEmpty()) "\n\n$body" else "")
                val proposal = PhotoMmsProposal.create(
                    description = description, sessionId = turn.sessionId,
                    imagePreviewPath = prepared.photo.absolutePath,
                    onDiscard = prepared::discard,
                    validate = {
                        missingPermission(needsContacts)?.let { error(it) }
                        if (request is PhotoMmsPolicy.Recipient.Contact) {
                            require(resolveContact(request.query) == recipient) { "The contact changed since preparation. Prepare the photo message again." }
                        }
                        PhotoMmsFiles.verifiedOriginal(photo)
                        PhotoMmsFiles.verifyDraft(prepared)
                        val currentConfig = PhotoMmsTransport.manager(context, subscription).carrierConfigValues
                        require(currentConfig.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, false)) { "MMS is no longer enabled for this SIM." }
                        require(prepared.pdu.length() <= currentConfig.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300 * 1024)) {
                            "The carrier's MMS size limit changed. Prepare the photo message again."
                        }
                        // Last local check after file hashing and any Contacts I/O, directly
                        // before the one-shot transport boundary.
                        refusal(turn, prepared.createdMs)?.let { error(it) }
                    },
                    submit = { PhotoMmsTransport.submit(context, prepared) },
                )
                if (PendingAction.propose(proposal)) {
                    ToolInvocation.AwaitingConfirmation(Redaction.redact(description),
                        "Photo MMS prepared for ${Redaction.redact(recipient.name)}. Review the image and phone number in the confirmation; nothing has been sent.")
                } else {
                    prepared.discard()
                    ToolInvocation.NotExecuted("The photo confirmation could not be shown in its conversation. Nothing was sent.")
                }
            } catch (cancelled: CancellationException) {
                draft?.discard()
                throw cancelled
            } catch (error: Exception) {
                draft?.discard()
                ToolInvocation.NotExecuted("Photo MMS not prepared: ${Redaction.redact(error.message ?: error.javaClass.simpleName)}")
            }
        }
    }

    private fun refusal(turn: PhotoTurn, createdMs: Long): String? = PhotoMmsPolicy.refusal(
        AppForeground.isForeground, ChatStore.activeSessionId.value, turn.sessionId,
        turn.selectedPhoto?.let { PhotoSelectionStore.isCurrent(turn.sessionId, it) } == true,
        createdMs, System.currentTimeMillis(),
    )

    private fun missingPermission(needsContacts: Boolean): String? {
        val capabilities = if (needsContacts) listOf(Capability.READ_CONTACTS, Capability.SEND_SMS)
            else listOf(Capability.SEND_SMS)
        for (capability in capabilities) {
            if (!capability.isGranted(context)) {
                PermissionSignal.request(capability)
                return Capability.unavailable(capability)
            }
        }
        return null
    }

    private fun resolveContact(query: String): PhotoMmsPolicy.Phone {
        val rows = mutableListOf<PhotoMmsPolicy.Phone>()
        // Treat % and _ as literal user name characters, not SQL search wildcards.
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        context.contentResolver.query(
            ContactPhone.CONTENT_URI, arrayOf(ContactPhone.CONTACT_ID, ContactPhone.DISPLAY_NAME, ContactPhone.NUMBER),
            "${ContactPhone.DISPLAY_NAME} LIKE ? ESCAPE '\\'", arrayOf("%$escaped%"), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                require(rows.size < 100) { "Too many contacts match. Use a more specific name." }
                rows.add(PhotoMmsPolicy.Phone(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
            }
        }
        return PhotoMmsPolicy.resolve(rows)
    }
}

/** No transport call exists until execute, even with Full Automation enabled. */
internal object PhotoMmsProposal {
    fun create(description: String, sessionId: String, imagePreviewPath: String,
               onDiscard: () -> Unit, validate: suspend () -> Unit, submit: suspend () -> String): PendingAction.Proposal {
        val used = AtomicBoolean(false)
        return PendingAction.Proposal(
            description = description, allowFullAutomation = false,
            sessionId = sessionId, imagePreviewPath = imagePreviewPath,
            // Decline consumes the same one-shot permit as approval. Deleting the
            // payload alone would leave a retained execute lambda authorised.
            onDiscard = { if (used.compareAndSet(false, true)) onDiscard() },
            execute = {
                withContext(Dispatchers.IO) {
                    if (!used.compareAndSet(false, true)) "MMS not sent again: this confirmation was already used."
                    else try {
                        validate()
                        submit()
                    } catch (cancelled: CancellationException) {
                        onDiscard()
                        throw cancelled
                    } catch (error: Exception) {
                        onDiscard()
                        "MMS not sent: ${Redaction.redact(error.message ?: error.javaClass.simpleName)}"
                    }
                }
            },
        )
    }
}
