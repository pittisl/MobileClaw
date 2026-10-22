package edu.pitt.isl.mobileclaw.notiftest

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

/**
 * Renders chat messages with distinct styles for user messages, assistant replies,
 * background decisions and errors. The sender and state remain visible while a
 * reply streams.
 */
class ChatAdapter(
    private val onActionClick: (ChatMessage, String) -> Unit,
    private val onRetryClick: (ChatMessage) -> Unit,
    private val onPhotoClick: (PhotoCandidate) -> Unit = {}
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).role == ChatMessage.Role.SYSTEM) TYPE_EVENT else TYPE_TURN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_EVENT) {
            EventViewHolder(inflater.inflate(R.layout.item_chat_event, parent, false))
        } else {
            MessageViewHolder(inflater.inflate(R.layout.item_chat_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is EventViewHolder   -> holder.bind(getItem(position))
            is MessageViewHolder -> holder.bind(getItem(position), onActionClick, onRetryClick, onPhotoClick)
        }
    }

    /**
     * An autonomous event on the rail. The stored content is a formatted block whose
     * first line is already a summary ("[Auto] WhatsApp: Priya", "⚡ Auto: Reply sent."),
     * so it splits on the first newline rather than being re-parsed — the writers of
     * that string are [NotifListener.surfaceDecision] and the confirm gate, and a
     * parser here would be a second place that has to agree with them.
     */
    class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val head: TextView = itemView.findViewById(R.id.eventHead)
        private val body: TextView = itemView.findViewById(R.id.eventBody)

        fun bind(m: ChatMessage) {
            val text = m.content.trim()
            val split = text.indexOf('\n')
            if (split < 0) {
                head.text = stripMarker(text)
                body.visibility = View.GONE
            } else {
                head.text = stripMarker(text.substring(0, split))
                body.text = linkPaths(itemView.context, SpannableStringBuilder(text.substring(split + 1).trim()))
                body.setTextIsSelectable(false)   // same re-arm as the bubble
                body.setTextIsSelectable(true)
                body.movementMethod = LinkMovementMethod.getInstance()
                body.visibility = View.VISIBLE
            }
        }

        /**
         * Drops the "[Auto] " prefix. It was there to distinguish these entries when
         * they were bubbles indistinguishable from everything else; on the rail the
         * node already says it, and the marker only costs horizontal space.
         */
        private fun stripMarker(line: String): String =
            line.removePrefix("[Auto]").trim()
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bubble: TextView       = itemView.findViewById(R.id.bubble)
        private val thinking: View         = itemView.findViewById(R.id.thinking)
        private val usage: TextView        = itemView.findViewById(R.id.usage)
        private val actionScroll: View     = itemView.findViewById(R.id.actionScroll)
        private val actionButtons: LinearLayout = itemView.findViewById(R.id.actionButtons)
        private val photoScroll: View = itemView.findViewById(R.id.photoScroll)
        private val photoCandidates: LinearLayout = itemView.findViewById(R.id.photoCandidates)
        private var boundPhotos: List<PhotoCandidate> = emptyList()

        fun bind(m: ChatMessage, onActionClick: (ChatMessage, String) -> Unit, onRetryClick: (ChatMessage) -> Unit,
                 onPhotoClick: (PhotoCandidate) -> Unit = {}) {
            // Pending tool progress replaces the animated indicator. Keep the
            // initial empty placeholder hidden until there is something to read.
            val hasContent = !m.pending || m.content.isNotBlank()
            thinking.visibility = if (m.pending && !hasContent) View.VISIBLE else View.GONE
            bubble.visibility = if (hasContent) View.VISIBLE else View.INVISIBLE
            bubble.text = if (hasContent) linkPaths(itemView.context,
                SpannableStringBuilder(renderMarkdown(m.content)), m.photos, onPhotoClick) else ""
            // Re-arm selection after every setText. A selectable TextView in a
            // recycled row otherwise ignores the first long-press after rebinding
            // (its selection controller is torn down by setText and only rebuilt by
            // this toggle), which reads as "copy doesn't work" rather than as a bug.
            bubble.setTextIsSelectable(false)
            bubble.setTextIsSelectable(true)
            // After the re-arm: setTextIsSelectable installs its own movement method,
            // which ignores ClickableSpans. Link handling on top keeps long-press select.
            bubble.movementMethod = LinkMovementMethod.getInstance()

            photoScroll.visibility = if (m.photos.isEmpty()) View.GONE else View.VISIBLE
            if (boundPhotos != m.photos) {
                boundPhotos = m.photos
                photoCandidates.removeAllViews()
                val dp = itemView.resources.displayMetrics.density
                for (photo in m.photos) {
                    val card = LinearLayout(itemView.context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams((108 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { marginEnd = (8 * dp).toInt() }
                    }
                    val image = ImageView(itemView.context).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (100 * dp).toInt())
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = "Preview photo: ${photo.name}"
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onPhotoClick(photo) }
                    }
                    card.addView(image)
                    card.addView(TextView(itemView.context).apply {
                        text = photo.capturedAtMs?.let { SimpleDateFormat("MMM d · HH:mm", Locale.US).format(Date(it)) }
                            ?: "Date unknown"
                        textSize = 12f
                        setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
                        maxLines = 1
                    })
                    card.addView(TextView(itemView.context).apply {
                        text = photo.name
                        textSize = 10f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                    photoCandidates.addView(card)
                    PhotoImages.load(image, photo.path)
                }
            }

            val bubbleParams = bubble.layoutParams as FrameLayout.LayoutParams
            val ctx = itemView.context
            when {
                m.role == ChatMessage.Role.USER -> {
                    bubbleParams.gravity = Gravity.END
                    bubble.setBackgroundResource(R.drawable.bg_chat_bubble_user)
                    bubble.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnPrimary))
                }
                m.isError -> {
                    bubbleParams.gravity = Gravity.START
                    bubble.setBackgroundResource(R.drawable.bg_chat_bubble_error)
                    bubble.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnErrorContainer))
                }
                else -> {
                    bubbleParams.gravity = Gravity.START
                    bubble.setBackgroundResource(R.drawable.bg_chat_bubble_assistant)
                    bubble.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurface))
                }
            }
            bubble.layoutParams = bubbleParams

            if (m.hasUsage) {
                usage.visibility = View.VISIBLE
                usage.text = "tokens: ${compact(m.promptTokens)} in / ${compact(m.completionTokens)} out"
            } else {
                usage.visibility = View.GONE
            }

            // Action buttons — retry chip for recoverable errors, action chips for normal
            // responses. An error whose cause is a missing or rejected API key gets no
            // chip: the next attempt fails the same way, and a button that never works is
            // worse than no button, because it hides the fact that the fix is elsewhere.
            actionButtons.removeAllViews()
            val showRetry = m.isError && m.retryable
            val showActions = !m.pending
                    && m.role == ChatMessage.Role.ASSISTANT
                    && (showRetry || m.actions.isNotEmpty())

            if (showActions) {
                actionScroll.visibility = View.VISIBLE
                val margin = (8 * itemView.resources.displayMetrics.density).toInt()
                val chipParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = margin }

                if (showRetry) {
                    actionButtons.addView(Chip(itemView.context).apply {
                        text = "Retry"
                        isClickable = true
                        setOnClickListener { onRetryClick(m) }
                        layoutParams = chipParams
                    })
                }
                m.actions.forEach { label ->
                    actionButtons.addView(Chip(itemView.context).apply {
                        text = label
                        isClickable = true
                        setOnClickListener { onActionClick(m, label) }
                        layoutParams = chipParams
                    })
                }
            } else {
                actionScroll.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val TYPE_TURN = 0
        private const val TYPE_EVENT = 1

        /**
         * Resolves a theme colour attribute. Bubble text colour has to be set in code
         * because one view serves three states (user / assistant / error) and each needs
         * a different `onX` role — the previous layout hard-coded `#000000` here, which
         * is precisely how dark mode broke without anyone noticing.
         */
        private fun themeColor(context: android.content.Context, attr: Int): Int {
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(attr, tv, true)
            return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
                   else tv.data
        }

        /**
         * File paths in a bubble open on tap ([FileLinks], [FileOpener]). Existence is
         * checked here so a path the model made up stays plain text.
         */
        fun linkPaths(context: android.content.Context, sb: SpannableStringBuilder,
                      photos: List<PhotoCandidate> = emptyList(), onPhoto: (PhotoCandidate) -> Unit = {}): SpannableStringBuilder {
            val message = sb.toString()
            return FileLinks.linkify(sb, exists = { java.io.File(it).isFile }, onOpen = { target ->
                val candidate = photos.filter { it.path == target || it.name == target }.singleOrNull()
                if (candidate != null) onPhoto(candidate) else FileOpener.open(context, target, message)
            })
        }

        /** "1.2k" past a thousand — the footer is a glance, not an accounting record. */
        fun compact(n: Int): String =
            if (n < 1000) n.toString() else String.format(Locale.US, "%.1fk", n / 1000.0)

        private val BOLD_RE   = Regex("""\*\*(.+?)\*\*""")
        private val ITALIC_RE = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")

        fun renderMarkdown(raw: String): CharSequence {
            // Pass 1: bold
            val sb = SpannableStringBuilder()
            var cursor = 0
            for (match in BOLD_RE.findAll(raw)) {
                if (match.range.first > cursor) sb.append(raw.substring(cursor, match.range.first))
                val start = sb.length
                sb.append(match.groupValues[1])
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                cursor = match.range.last + 1
            }
            if (cursor < raw.length) sb.append(raw.substring(cursor))

            // Pass 2: italic on the assembled string
            var offset = 0
            for (match in ITALIC_RE.findAll(sb.toString())) {
                val start = match.range.first - offset
                val end   = match.range.last  - offset + 1
                val inner = match.groupValues[1]
                sb.replace(start, end, inner)
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, start + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offset += match.value.length - inner.length
            }
            return sb
        }

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) =
                a.timestampMs == b.timestampMs && a.role == b.role
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }
}
