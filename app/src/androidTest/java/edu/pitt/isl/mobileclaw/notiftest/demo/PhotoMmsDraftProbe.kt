package edu.pitt.isl.mobileclaw.notiftest.demo

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AgentRunState
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.awaitUntil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Opt-in, no-model/no-send probe of EXIF ingestion and the real SMS app's image composer.
 * The only permitted click selects the fictional conversation in the native picker.
 * No sending API or send-button click is present. Leaves the draft for host inspection.
 */
@RunWith(AndroidJUnit4::class)
class PhotoMmsDraftProbe {
    @Test fun photoMetadataAndNativeMmsDraft() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e photoMmsDraft true", args.getString("photoMmsDraft") == "true")
        val runId = requireNotNull(args.getString("photoRunId"))
        require(runId.matches(Regex("PHOTOMMS-[0-9-]{8,32}")))
        val chooseRecipient = args.getString("chooseRecipient") == "true"
        val photo = File(requireNotNull(args.getString("photoPath"))).canonicalFile
        require(photo.path.startsWith("/storage/emulated/0/Pictures/MobileClawPhotoDemo/"))
        require(photo.isFile && photo.extension.lowercase() == "jpg")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val output = File(context.filesDir, "demo-photo-mms/$runId").apply { mkdirs() }
        val recipient = "+12025550198" // NANP reserved fictional 555-01xx number.
        val body = "MobileClaw photo draft $runId. Not sent."
        val report = JSONObject().put("runId",runId).put("recipient",recipient)
            .put("liveLlm",false).put("sendApiCalled",false).put("sendButtonClicked",false)
            .put("recipientMode",if (chooseRecipient) "native-picker" else "prefilled-address")
            .put("scope","Native image composer and EXIF ingestion only; not an end-to-end MobileClaw photo-sharing feature")
        fun shell(command: String): String = ui.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        fun captureTree(node: AccessibilityNodeInfo?, rows: JSONArray = JSONArray()): JSONArray {
            if (node == null) return rows
            rows.put(JSONObject().put("package",node.packageName?.toString())
                .put("id",node.viewIdResourceName).put("class",node.className?.toString())
                .put("text",node.text?.toString()).put("description",node.contentDescription?.toString())
                .put("visible",node.isVisibleToUser).put("enabled",node.isEnabled))
            for (i in 0 until node.childCount) captureTree(node.getChild(i),rows)
            return rows
        }
        fun outboundIds(uri: String, selection: String): List<Long> =
            context.contentResolver.query(Uri.parse(uri),arrayOf("_id"),selection,null,"_id ASC")!!.use { c ->
                buildList { while(c.moveToNext()) add(c.getLong(0)) }
            }
        fun findFixtureRecipient(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.viewIdResourceName == "com.android.messaging:id/name" &&
                node.text?.toString()?.filter(Char::isDigit) == "2025550198") return node
            for (i in 0 until node.childCount) findFixtureRecipient(node.getChild(i))?.let { return it }
            return null
        }
        try {
            AppSettings.init(context)
            assertTrue("Probe requires the already-paused assistant",AgentRunState.isPaused)
            assertFalse("Probe requires Full Automation off",AppSettings.fullAutomation)
            val smsPackage = shell("cmd role get-role-holders --user 0 android.app.role.SMS")
            require(smsPackage.matches(Regex("[A-Za-z0-9_.]+")))
            report.put("defaultSmsPackage",smsPackage)
            val metadata = JSONArray()
            val photos = photo.parentFile!!.listFiles()!!.filter { it.extension == "jpg" }.sortedBy { it.name }
            assertEquals("Use the isolated five-photo fixture directory",5,photos.size)
            for (f in photos) {
                val exif = ExifInterface(f.path)
                val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                val offset = exif.getAttribute("OffsetTimeOriginal")
                assertNotNull("DateTimeOriginal missing: ${f.name}",original)
                assertEquals("-04:00",offset)
                val entry = JSONObject().put("name",f.name).put("dateTimeOriginal",original)
                    .put("offsetTimeOriginal",offset)
                    .put("dateTimeDigitized",exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
                    .put("sha256",MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) })
                context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DATE_TAKEN),
                    "${MediaStore.Images.Media.DATA} = ?",arrayOf(f.path),null)!!.use { c ->
                    assertTrue("Media scanner did not index ${f.name}",c.moveToFirst())
                    val taken = c.getLong(1)
                    val expected = LocalDateTime.parse(original,DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                        .toInstant(ZoneOffset.of(offset)).toEpochMilli()
                    assertEquals("MediaStore capture time differs from EXIF",expected,taken)
                    entry.put("mediaStoreId",c.getLong(0)).put("mediaStoreDateTakenMs",taken)
                }
                metadata.put(entry)
            }
            report.put("photos",metadata)
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_SMS)
            val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            assertTrue("No active default SMS subscription",subscriptions.any { it.subscriptionId == subId })
            val config = context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId).carrierConfigValues
            report.put("subscriptionId",subId).put("mmsEnabled",config.getBoolean("enabledMMS"))
                .put("maxMessageSize",config.getInt("maxMessageSize"))
                .put("maxImageWidth",config.getInt("maxImageWidth"))
                .put("maxImageHeight",config.getInt("maxImageHeight"))
            assertTrue("MMS disabled for default SMS subscription",config.getBoolean("enabledMMS"))
            val smsBefore = outboundIds("content://sms","type IN (2,4,5,6)")
            val mmsBefore = outboundIds("content://mms","msg_box IN (2,4)")
            ui.dropShellPermissionIdentity()
            val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",photo)
            report.put("attachmentUri",uri.toString()).put("attachmentBytes",photo.length())
            val intent = Intent(Intent.ACTION_SEND).setPackage(smsPackage).setType("image/jpeg")
                .putExtra("sms_body",body).putExtra(Intent.EXTRA_TEXT,body)
                .putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!chooseRecipient) intent.putExtra("address",recipient)
            intent.clipData = ClipData.newUri(context.contentResolver,"Demo dog photo",uri)
            instrumentation.runOnMainSync { context.startActivity(intent) }
            var tree = JSONArray()
            awaitUntil(timeoutMs=15_000,message="Native composer did not show the labelled draft") {
                tree = captureTree(ui.rootInActiveWindow)
                if (chooseRecipient) ui.rootInActiveWindow?.packageName?.toString() == smsPackage
                else tree.toString().contains(runId)
            }
            if (chooseRecipient) {
                // Keep the FileProvider process alive until the receiving app has
                // consumed the attachment; do not finish instrumentation at the picker.
                var target: AccessibilityNodeInfo? = null
                awaitUntil(timeoutMs=5_000,message="Fictional conversation is absent from native picker") {
                    target = findFixtureRecipient(ui.rootInActiveWindow)
                    target != null
                }
                var clickable = target!!
                while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
                assertTrue("Fictional conversation row is not clickable",clickable.isClickable)
                assertTrue("Could not select fictional conversation",clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                report.put("fictionalRecipientSelected",true)
                awaitUntil(timeoutMs=10_000,message="Image did not reach the native composer") {
                    tree = captureTree(ui.rootInActiveWindow)
                    (0 until tree.length()).any {
                        val n = tree.getJSONObject(it)
                        n.optBoolean("visible") && n.optString("class").endsWith("ImageView") &&
                            (n.optString("id").contains("attachment") || n.optString("description").startsWith("Image"))
                    }
                }
            }
            // Accessibility nodes arrive before the launch animation has finished.
            // Wait for the real composer to settle before capturing visual evidence.
            ui.waitForIdle(500,5_000)
            tree = captureTree(ui.rootInActiveWindow)
            // The package's own visible UI must identify this as an MMS draft with an image.
            (output.resolve("composer-ui.json")).writeText(tree.toString(2)+"\n")
            ui.takeScreenshot()?.let { bitmap ->
                output.resolve("composer.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            report.put("composerUiCaptured",true).put("composerPackage",ui.rootInActiveWindow?.packageName?.toString())
            assertEquals(smsPackage,ui.rootInActiveWindow?.packageName?.toString())
            // Never search the entire tree for "photo"/"MMS": our draft's own body
            // contains those words, which would accept a text-only failed handoff.
            val visible = (0 until tree.length()).map { tree.getJSONObject(it) }.filter { it.optBoolean("visible") }
                assertTrue("Recipient not visible in composer",visible.any {
                    it.optString("id").endsWith("/conversation_title") && it.optString("text").filter(Char::isDigit).contains("2025550198")
                })
                val hasImage = visible.any {
                    it.optString("class").endsWith("ImageView") &&
                        (it.optString("id").contains("attachment") || it.optString("description").startsWith("Image"))
                }
                report.put("visibleImageAttachment",hasImage)
                assertTrue("Image attachment missing: native handoff is text-only",hasImage)
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_SMS)
            assertEquals("Outgoing SMS provider rows changed",smsBefore,outboundIds("content://sms","type IN (2,4,5,6)"))
            assertEquals("Outgoing MMS provider rows changed",mmsBefore,outboundIds("content://mms","msg_box IN (2,4)"))
            report.put("outboundProviderRowsUnchanged",true)
                .put("status","passed")
        } catch(t: Throwable) {
            report.put("status","failed").put("error","${t.javaClass.simpleName}: ${t.message}")
            throw t
        } finally {
            ui.dropShellPermissionIdentity()
            report.put("assistantRemainedPaused",AgentRunState.isPaused)
            output.resolve("report.json").writeText(report.toString(2)+"\n")
        }
    }
}
