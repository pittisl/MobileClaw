package edu.pitt.isl.mobileclaw.notiftest

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Opens a file the user tapped in the chat with whatever app handles it.
 *
 * Two routes, because they give different results on the phone:
 *  - **Media** (photos, video, audio) resolve to their `MediaStore` id, so the gallery
 *    opens the picture the way it would from its own grid — no provider of ours involved.
 *  - **Everything else** goes through our `FileProvider` (`<applicationId>.files`, rooted at
 *    external storage) with a one-shot read grant; the MIME type comes from the extension,
 *    with Markdown and other note formats offered as `text/plain` because that is what
 *    viewers actually register for.
 *
 * Read-only by construction: `ACTION_VIEW` plus a read grant is all the intent carries.
 */
object FileOpener {

    private val TEXT_LIKE = setOf("md", "markdown", "txt", "log", "csv", "tsv", "json", "ini", "cfg", "conf", "yaml", "yml", "rst", "tex", "bib", "srt", "vtt")

    /**
     * [target] is a full path or a bare file name from the chat; [messageText] is the
     * message it was tapped in, whose directories are tried first for a bare name.
     */
    fun open(context: Context, target: String, messageText: CharSequence = "") {
        val file = resolve(context, target, messageText)
        if (file == null || !file.isFile) {
            Toast.makeText(context, "File not found: ${File(target).name}", Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = mediaUri(context, file) ?: providerUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)

    /**
     * A full path resolves to itself. A bare name is tried under each directory the message
     * mentions, then looked up by display name in `MediaStore` (photos and media the scanner
     * knows), then under the two folders the demo preload uses.
     */
    fun resolve(context: Context, target: String, messageText: CharSequence): File? {
        if (target.startsWith("/")) return canonical(File(target))
        for (dir in FileLinks.directoriesIn(messageText)) {
            val f = canonical(File(dir, target)); if (f.isFile) return f
        }
        byDisplayName(context, target)?.let { return it }
        for (dir in listOf("/storage/emulated/0/DCIM/Camera", "/storage/emulated/0/Documents/demo", "/storage/emulated/0/Download", "/storage/emulated/0/Documents")) {
            val f = File(dir, target); if (f.isFile) return f
        }
        return null
    }

    private fun byDisplayName(context: Context, name: String): File? {
        val files = MediaStore.Files.getContentUri("external")
        try {
            context.contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns.DATA), "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { c ->
                while (c.moveToNext()) {
                    val f = File(c.getString(0) ?: continue); if (f.isFile) return f
                }
            }
        } catch (e: Exception) { /* no MediaStore answer: fall through */ }
        return null
    }

    /** `MediaStore` row for [file], if the media scanner knows it and it is image/video/audio. */
    private fun mediaUri(context: Context, file: File): Pair<Uri, String>? {
        val files = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MIME_TYPE)
        try {
            context.contentResolver.query(files, projection, "${MediaStore.Files.FileColumns.DATA} = ?", arrayOf(file.path), null)?.use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getLong(0)
                val collection = when (c.getInt(1)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> return null
                }
                val mime = c.getString(2) ?: mimeOf(file)
                return ContentUris.withAppendedId(collection, id) to mime
            }
        } catch (e: Exception) {
            // Fall through to the provider route: a failed lookup is not a failed open.
        }
        return null
    }

    private fun providerUri(context: Context, file: File): Pair<Uri, String> =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file) to mimeOf(file)

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext in TEXT_LIKE) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
