package io.github.z3275630ops.floatingnotes.feature.share

import android.content.Intent
import java.net.URI

internal sealed interface SharePayload {
    data class Text(val body: String, val sourceUrl: String?) : SharePayload
    data class Rejected(val message: String) : SharePayload

    companion object {
        const val MAX_LENGTH = 100_000

        fun read(intent: Intent): SharePayload {
            val raw = try {
                when (intent.action) {
                    Intent.ACTION_SEND -> {
                        if (intent.type !in setOf("text/plain", "text/uri-list") ||
                            intent.hasExtra(Intent.EXTRA_STREAM)
                        ) return Rejected("目前可以收集纯文本和链接。")
                        val clip = intent.clipData
                        if (clip != null && (clip.itemCount != 1 || clip.getItemAt(0).uri != null)) {
                            return Rejected("目前可以收集纯文本和链接。")
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
    }
}
