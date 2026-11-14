package io.github.z3275630ops.floatingnotes.feature.share

import android.content.Intent
import android.net.Uri
import io.github.z3275630ops.floatingnotes.data.local.AttachmentKind
import java.net.URI

internal sealed interface SharePayload {
    data class Text(val body: String, val sourceUrl: String?) : SharePayload
    data class Media(val files: List<Uri>, val mime: String, val caption: String) : SharePayload
    data class Rejected(val message: String) : SharePayload

    companion object {
        const val MAX_LENGTH = 100_000

        fun read(intent: Intent): SharePayload {
            val raw = try {
                val mime = intent.type
                if ((intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) &&
                    mime != null && AttachmentKind.fromMime(mime) != null
                ) return readMedia(intent, mime)
                when (intent.action) {
                    Intent.ACTION_SEND -> {
                        if (intent.type !in setOf("text/plain", "text/uri-list") ||
                            intent.hasExtra(Intent.EXTRA_STREAM)
                        ) return Rejected("可收集文字、链接、图片、音频和视频。")
                        val clip = intent.clipData
                        if (clip != null && (clip.itemCount != 1 || clip.getItemAt(0).uri != null)) {
                            return Rejected("可收集文字、链接、图片、音频和视频。")
                        }
                        intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: clip?.getItemAt(0)?.text
                    }
                    Intent.ACTION_PROCESS_TEXT -> {
                        if (intent.type != "text/plain") return Rejected("无法读取这种选区内容。")
                        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    }
                    else -> return Rejected("没有可收集的内容。")
                }
            } catch (_: RuntimeException) {
                return Rejected("无法读取来源应用发来的内容。")
            }
            if (raw == null || raw.isBlank()) return Rejected("来源应用没有提供文字。")
            if (raw.length > MAX_LENGTH) return Rejected("文字超过十万字符，请分段后重新分享。")
            val body = raw.toString()
            val link = runCatching { URI(body.trim()) }.getOrNull()?.takeIf {
                (it.scheme.equals("https", true) || it.scheme.equals("http", true)) &&
                    !it.host.isNullOrEmpty() && it.rawUserInfo == null
            }
            return Text(body, link?.toString())
        }

        @Suppress("DEPRECATION")
        private fun readMedia(intent: Intent, mime: String): SharePayload {
            val clip = intent.clipData
            val files = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            } else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            val uris = files.ifEmpty {
                List(clip?.itemCount ?: 0) { clip?.getItemAt(it)?.uri }.filterNotNull()
            }
            val caption = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            if (uris.isEmpty() || uris.any { it.scheme != "content" }) return Rejected("来源应用没有提供可读附件。")
            if (uris.size > 10) return Rejected("一次最多收集 10 个附件。")
            if (caption.length > MAX_LENGTH) return Rejected("文字超过十万字符，请分段后重新分享。")
            return Media(uris, mime, caption)
        }
    }
}
