package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import edu.pitt.isl.mobileclaw.notiftest.ActionLogStore
import edu.pitt.isl.mobileclaw.notiftest.ChatMessage
import edu.pitt.isl.mobileclaw.notiftest.ChatStore
import edu.pitt.isl.mobileclaw.notiftest.Redaction
import java.io.File

/** Restricted to the dedicated MMS directory, and never permits a writer. */
class PhotoMmsProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "MMS payloads are read-only." }
        return super.openFile(uri, mode) ?: throw java.io.FileNotFoundException("MMS payload is unavailable.")
    }

    override fun getType(uri: Uri): String = if (uri.lastPathSegment == "message.pdu")
        "application/vnd.wap.mms-message" else super.getType(uri) ?: "application/octet-stream"
}

internal object PhotoMmsTransport {
    @Suppress("DEPRECATION")
    fun manager(context: Context, subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)

    fun defaultSubscription(): Int = SubscriptionManager.getDefaultSmsSubscriptionId().also {
        require(SubscriptionManager.isValidSubscriptionId(it)) { "Choose a default SIM for SMS/MMS in Android Settings first." }
    }

    /** Called only by the approved proposal. The binder return is not a send receipt. */
    fun submit(context: Context, draft: PhotoMmsFiles.Draft): String {
        require(defaultSubscription() == draft.subscriptionId) { "The default messaging SIM changed. Prepare a new message." }
        PhotoMmsFiles.verifyDraft(draft)
        val record = PhotoMmsFiles.readRecord(draft.directory) ?: error("Prepared MMS no longer exists.")
        check(record.optString("state") == "prepared") { "This message was already submitted or discarded; it will not be sent again." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", draft.pdu)
        val callbackIntent = Intent(context, PhotoMmsSentReceiver::class.java)
            .setAction(ACTION_SENT).setData(Uri.parse("mobileclaw-mms:${draft.id}"))
            .putExtra("draftId", draft.id)
        val callback = PendingIntent.getBroadcast(context, 0, callbackIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        // Commit before IPC: the callback can race the binder return, and a process
        // death must never make a retry look like a fresh unsent confirmation.
        record.put("state", "submitted")
        PhotoMmsFiles.writeRecord(draft.directory, record)
        return try {
            // Android's MMS broker grants this exact URI to its phone/carrier service.
            // There is no broad grant, exported provider, or handoff to another app UI.
            manager(context, draft.subscriptionId).sendMultimediaMessage(context, uri, null, null, callback)
            val result = "MMS submitted to Android for ${Redaction.redact(record.getString("name"))}. Carrier confirmation is pending; delivery is not confirmed."
            ActionLogStore.record("send_photo", draft.id, "submit MMS", null, result, success = false)
            result
        } catch (error: Exception) {
            callback.cancel()
            record.put("state", "submission_failed")
            PhotoMmsFiles.writeRecord(draft.directory, record)
            draft.pdu.delete(); draft.photo.delete()
            val result = "MMS submission failed: ${Redaction.redact(error.message ?: error.javaClass.simpleName)}. No automatic retry will be made."
            ActionLogStore.record("send_photo", draft.id, "submit MMS", null, result, success = false)
            result
        }
    }

    const val ACTION_SENT = "edu.pitt.isl.mobileclaw.PHOTO_MMS_SENT"
}

/** The immutable PendingIntent is the sole way the telephony stack can report this result. */
class PhotoMmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhotoMmsTransport.ACTION_SENT) return
        val id = intent.getStringExtra("draftId") ?: return
        if (intent.data?.toString() != "mobileclaw-mms:$id") return
        val directory = PhotoMmsFiles.directory(context, id) ?: return
        val record = PhotoMmsFiles.readRecord(directory) ?: return
        if (record.optString("state") != "submitted") return
        val successful = resultCode == Activity.RESULT_OK
        val result = PhotoMmsReceipt.render(Redaction.redact(record.getString("name")), resultCode)
        record.put("state", if (successful) "carrier_sent" else "send_failed")
            .put("resultCode", resultCode).put("result", result)
        PhotoMmsFiles.writeRecord(directory, record)
        ActionLogStore.record("send_photo", id, "MMS carrier result", null, result, success = successful)
        ChatStore.add(record.getString("sessionId"), ChatMessage(role = ChatMessage.Role.SYSTEM, content = result))
        val pdu = File(directory, "message.pdu")
        runCatching {
            context.revokeUriPermission(FileProvider.getUriForFile(context, "${context.packageName}.mms", pdu), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pdu.delete()
        File(directory, "photo.jpg").delete()
    }
}

/** Kept pure so a platform callback code cannot silently become a delivery claim. */
internal object PhotoMmsReceipt {
    fun render(name: String, code: Int): String = if (code == Activity.RESULT_OK)
        "MMS sent to $name: Android reported successful carrier submission. Receipt on the recipient's phone is not confirmed."
    else "MMS to $name failed (Android MMS result $code). It was not confirmed sent; no automatic retry will be made."
}
