package io.github.z3275630ops.floatingnotes.feature.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class ShareCardTemplate(val label: String) {
    Glass("玻璃渐变"),
    Minimal("纯净简洁"),
    Paper("暖白纸感"),
}

internal data class ShareCardOptions(
    val template: ShareCardTemplate = ShareCardTemplate.Glass,
    val showTitle: Boolean = true,
    val showTimestamp: Boolean = true,
    val showTags: Boolean = true,
    /** Off by default so private source URLs or system origin paths are never shared unless opted in (Q056). */
    val includeSourceMetadata: Boolean = false,
)

internal data class ShareCardPageContent(
    val pageIndex: Int,
    val totalPages: Int,
    val headerTitle: String?,
    val timestampLabel: String?,
    val bodyLines: List<String>,
    val tagLabels: List<String>,
    val sourceMetaLabel: String?,
)

internal data class ShareCardPreparedContent(
    val pages: List<ShareCardPageContent>,
    val unsupportedMediaCount: Int,
    val imageAttachmentCount: Int,
)

internal object ShareCardPaginator {

    fun buildPreparedContent(
        note: NoteEntity,
        tagNames: List<String>,
        options: ShareCardOptions,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ShareCardPreparedContent {
        @Suppress("UNCHECKED_CAST")
        val rawBlocks = runCatching {
            MiniJson.parseObject(note.documentJson)["blocks"] as? List<Map<String, Any?>>
        }.getOrNull()
        val displayTitle = LocalRetrievalEngine.displayNoteTitle(note)

        var unsupportedMediaCount = 0
        var imageAttachmentCount = 0
        val paragraphs = mutableListOf<String>()

        if (rawBlocks != null) {
            rawBlocks.forEach { block ->
                val blockType = (block["type"] as? String).orEmpty().lowercase()
                val text = (block["text"] as? String).orEmpty()
                val checked = (block["checked"] as? Boolean) == true
                val label = (block["label"] as? String).orEmpty()
                val attachmentId = block["attachment_id"] as? String
                when (blockType) {
                    "paragraph" -> {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) paragraphs += trimmed
                    }
                    "checkbox" -> {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            val mark = if (checked) "☑" else "☐"
                            paragraphs += "$mark $trimmed"
                        }
                    }
                    "image" -> {
                        imageAttachmentCount++
                        paragraphs += "[图片附件]"
                    }
                    "audio", "video" -> unsupportedMediaCount++
                    "reference" -> {
                        val targetLabel = label.trim().ifEmpty { text.trim() }.ifEmpty { "引用笔记" }
                        paragraphs += "↳ $targetLabel"
                    }
                    else -> {
                        if (!attachmentId.isNullOrBlank()) {
                            unsupportedMediaCount++
                        } else {
                            val trimmed = text.trim()
                            if (trimmed.isNotEmpty()) paragraphs += trimmed
                        }
                    }
                }
            }
        } else {
            val fallback = LocalRetrievalEngine.bodyExcludingTitle(note, title = displayTitle)
            if (fallback.isNotBlank()) paragraphs += fallback.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        // Strip leading paragraph if it duplicates displayTitle when showTitle is enabled
        if (options.showTitle && paragraphs.size > 1 && paragraphs.first() == displayTitle) {
            paragraphs.removeAt(0)
        }
        if (paragraphs.isEmpty()) {
            paragraphs += if (options.showTitle) "（仅标题记录）" else displayTitle
        }

        val timestampLabel = if (options.showTimestamp) {
            runCatching {
                Instant.parse(note.createdAt).atZone(zoneId)
                    .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
            }.getOrNull()
        } else null

        val sourceMetaLabel = if (options.includeSourceMetadata) {
            val sourceDesc = when {
                !note.sourceUrl.isNullOrBlank() -> "来源：${note.sourceUrl}"
                note.authorType != "user" -> "来源：本机整理生成"
                note.sourceType == "share" -> "来源：系统分享收集"
                else -> "来源：浮记本机记录"
            }
            sourceDesc
        } else null

        val chunks = paginateParagraphs(paragraphs, maxUnitsPerPage = 460)
        val total = chunks.size
        val pages = chunks.mapIndexed { idx, lines ->
            ShareCardPageContent(
                pageIndex = idx + 1,
                totalPages = total,
                headerTitle = if (options.showTitle) displayTitle else null,
                timestampLabel = timestampLabel,
                bodyLines = lines,
                tagLabels = if (options.showTags) tagNames.take(5) else emptyList(),
                sourceMetaLabel = sourceMetaLabel,
            )
        }
        return ShareCardPreparedContent(
            pages = pages,
            unsupportedMediaCount = unsupportedMediaCount,
            imageAttachmentCount = imageAttachmentCount,
        )
    }

    /** Splits long notes into clean multi-page slices while keeping every paragraph intact where possible. */
    fun paginateParagraphs(paragraphs: List<String>, maxUnitsPerPage: Int = 460): List<List<String>> {
        if (paragraphs.isEmpty()) return listOf(emptyList())
        val pages = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentUnits = 0

        fun appendPiece(piece: String) {
            val cost = piece.length + 28
            if (current.isNotEmpty() && currentUnits + cost > maxUnitsPerPage) {
                pages += current
                current = mutableListOf()
                currentUnits = 0
            }
            current += piece
            currentUnits += cost
        }

        paragraphs.forEach { para ->
            if (para.length + 28 <= maxUnitsPerPage) {
                appendPiece(para)
            } else {
                // Hard-split oversized single paragraph safely on sentence/character boundaries
                var start = 0
                val step = (maxUnitsPerPage - 40).coerceAtLeast(120)
                while (start < para.length) {
                    val end = (start + step).coerceAtMost(para.length)
                    appendPiece(para.substring(start, end))
                    start = end
                }
            }
        }
        if (current.isNotEmpty()) pages += current
        return if (pages.isEmpty()) listOf(emptyList()) else pages
    }
}

internal data class RenderedShareCard(
    val pageIndex: Int,
    val bitmap: Bitmap,
    val file: File,
    val contentUri: Uri,
)

internal object ShareCardRenderer {

    private const val CARD_WIDTH = 1080

    fun renderAllPages(
        context: Context,
        noteId: String,
        prepared: ShareCardPreparedContent,
        template: ShareCardTemplate,
    ): List<RenderedShareCard> {
        val dir = File(context.cacheDir, "share_cards").apply { mkdirs() }
        // Clean older temp share cards for tidy cache
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }

        val stamp = System.currentTimeMillis()
        val authority = "${context.packageName}.attachments"
        return prepared.pages.map { page ->
            val bitmap = renderPageBitmap(page, template)
            val file = File(dir, "share_${noteId.take(8)}_${stamp}_p${page.pageIndex}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(context, authority, file)
            RenderedShareCard(
                pageIndex = page.pageIndex,
                bitmap = bitmap,
                file = file,
                contentUri = uri,
            )
        }
    }

    private fun renderPageBitmap(page: ShareCardPageContent, template: ShareCardTemplate): Bitmap {
        val outerPad = 64f
        val cardLeft = outerPad
        val cardRight = CARD_WIDTH - outerPad
        val innerPad = 56f
        val textWidth = (cardRight - cardLeft - innerPad * 2).toInt()

        val palette = when (template) {
            ShareCardTemplate.Glass -> ThemeColors(
                bgStart = Color.parseColor("#E3F5EB"),
                bgEnd = Color.parseColor("#D5EBDF"),
                cardBg = Color.parseColor("#FAFFFC"),
                cardBorder = Color.parseColor("#BBE0CC"),
                titleColor = Color.parseColor("#11281E"),
                bodyColor = Color.parseColor("#243B30"),
                mutedColor = Color.parseColor("#5C7A6B"),
                accentColor = Color.parseColor("#187D52"),
            )
            ShareCardTemplate.Minimal -> ThemeColors(
                bgStart = Color.parseColor("#F5F7F6"),
                bgEnd = Color.parseColor("#EEF2F0"),
                cardBg = Color.parseColor("#FFFFFF"),
                cardBorder = Color.parseColor("#E2E8E5"),
                titleColor = Color.parseColor("#151C19"),
                bodyColor = Color.parseColor("#2E3833"),
                mutedColor = Color.parseColor("#73827A"),
                accentColor = Color.parseColor("#187D52"),
            )
            ShareCardTemplate.Paper -> ThemeColors(
                bgStart = Color.parseColor("#F4EFE6"),
                bgEnd = Color.parseColor("#EBE3D5"),
                cardBg = Color.parseColor("#FCF9F2"),
                cardBorder = Color.parseColor("#E0D6C5"),
                titleColor = Color.parseColor("#2C241B"),
                bodyColor = Color.parseColor("#443A2E"),
                mutedColor = Color.parseColor("#8A7D6C"),
                accentColor = Color.parseColor("#257A57"),
            )
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.titleColor
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.bodyColor
            textSize = 36f
            typeface = Typeface.DEFAULT
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.mutedColor
            textSize = 27f
            typeface = Typeface.DEFAULT
        }
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentColor
            textSize = 27f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val titleLayout = page.headerTitle?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, titlePaint, textWidth)
                .setLineSpacing(10f, 1.0f)
                .build()
        }
        val bodyText = page.bodyLines.joinToString("\n\n")
        val bodyLayout = StaticLayout.Builder.obtain(bodyText, 0, bodyText.length, bodyPaint, textWidth)
            .setLineSpacing(16f, 1.0f)
            .build()

        val tagText = if (page.tagLabels.isNotEmpty()) page.tagLabels.joinToString("  ") { "#$it" } else null
        val tagLayout = tagText?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, brandPaint, textWidth).build()
        }
        val sourceLayout = page.sourceMetaLabel?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, metaPaint, textWidth).build()
        }

        val headerHeight = (page.timestampLabel?.let { 44f } ?: 0f) +
            (titleLayout?.let { it.height.toFloat() + 26f } ?: 0f)
        val footerHeight = (tagLayout?.let { it.height.toFloat() + 20f } ?: 0f) +
            (sourceLayout?.let { it.height.toFloat() + 18f } ?: 0f) +
            66f
        val contentHeight = innerPad * 2 + headerHeight + bodyLayout.height.toFloat() + 24f + footerHeight
        val totalHeight = (outerPad * 2 + contentHeight).toInt().coerceIn(720, 2200)

        val bitmap = Bitmap.createBitmap(CARD_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Outer background gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, CARD_WIDTH.toFloat(), totalHeight.toFloat(),
                palette.bgStart, palette.bgEnd, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), totalHeight.toFloat(), bgPaint)

        // Card container
        val cardRect = RectF(cardLeft, outerPad, cardRight, totalHeight - outerPad)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.cardBg }
        canvas.drawRoundRect(cardRect, 40f, 40f, cardPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = palette.cardBorder
        }
        canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)

        var y = outerPad + innerPad
        val x = cardLeft + innerPad

        page.timestampLabel?.let { ts ->
            canvas.drawText(ts, x, y + 26f, metaPaint)
            y += 46f
        }
        titleLayout?.let { layout ->
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 28f
        }

        // Body
        canvas.save()
        canvas.translate(x, y)
        bodyLayout.draw(canvas)
        canvas.restore()
        y += bodyLayout.height + 28f

        tagLayout?.let { layout ->
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 18f
        }
        sourceLayout?.let { layout ->
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 18f
        }

        // Divider + Footer provenance & pagination
        val dividerY = cardRect.bottom - innerPad - 42f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardBorder
            strokeWidth = 2f
        }
        canvas.drawLine(x, dividerY, cardRight - innerPad, dividerY, linePaint)

        val footerY = dividerY + 38f
        canvas.drawText("浮记 · Floating Notes", x, footerY, brandPaint)
        if (page.totalPages > 1) {
            val pageStr = "第 ${page.pageIndex} / ${page.totalPages} 页"
            val pageWidth = metaPaint.measureText(pageStr)
            canvas.drawText(pageStr, cardRight - innerPad - pageWidth, footerY, metaPaint)
        }
        return bitmap
    }

    private data class ThemeColors(
        val bgStart: Int,
        val bgEnd: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val titleColor: Int,
        val bodyColor: Int,
        val mutedColor: Int,
        val accentColor: Int,
    )
}
