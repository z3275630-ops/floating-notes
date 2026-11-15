package io.github.z3275630ops.floatingnotes.feature.widget

import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewSessionEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object WidgetContract {
    const val EXTRA_WIDGET_ACTION = "io.github.z3275630ops.floatingnotes.EXTRA_WIDGET_ACTION"
    const val EXTRA_REVIEW_SESSION_ID = "io.github.z3275630ops.floatingnotes.EXTRA_REVIEW_SESSION_ID"
    const val EXTRA_NOTE_ID = "io.github.z3275630ops.floatingnotes.EXTRA_NOTE_ID"

    const val ACTION_QUICK_CAPTURE = "quick_capture"
    const val ACTION_OPEN_EXPLORE = "open_explore"
    const val ACTION_OPEN_AGENT = "open_agent"
    const val ACTION_OPEN_REVIEW_SESSION = "open_review_session"
    const val ACTION_REFRESH_WIDGET = "io.github.z3275630ops.floatingnotes.ACTION_REFRESH_WIDGET"
}

internal data class ReviewWidgetSnapshot(
    val sessionId: String?,
    val primaryNoteId: String?,
    val privacyModeEnabled: Boolean,
    val privacyBadge: String,
    val headline: String,
    val body: String,
    val updatedTimeLabel: String,
    val activeNotesCount: Int,
)

internal object FloatingNotesWidgetStateBuilder {

    fun buildReviewSnapshot(
        latestSession: ReviewSessionEntity?,
        notes: List<NoteEntity>,
        privacyModeEnabled: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): ReviewWidgetSnapshot {
        val activeNotes = notes.filter { it.deletedAt == null }
        val activeById = activeNotes.associateBy { it.id }

        val sessionCardIds = latestSession?.cardIdsJson?.let(::parseStringArray).orEmpty()
        val sessionNotes = sessionCardIds.mapNotNull { activeById[it] }
        val primaryNote = sessionNotes.firstOrNull() ?: activeNotes.firstOrNull()

        val updatedInstant = latestSession?.createdAt?.let(::parseInstant)
            ?: primaryNote?.updatedAt?.let(::parseInstant)
            ?: now
        val timeFmt = DateTimeFormatter.ofPattern("MM/dd HH:mm")
        val updatedTimeLabel = "更新于 ${updatedInstant.atZone(zoneId).format(timeFmt)}"
        val privacyBadge = if (privacyModeEnabled) "隐私遮罩已开" else "正文预览"

        if (activeNotes.isEmpty() || primaryNote == null) {
            return ReviewWidgetSnapshot(
                sessionId = null,
                primaryNoteId = null,
                privacyModeEnabled = privacyModeEnabled,
                privacyBadge = privacyBadge,
                headline = "还没有待回顾笔记",
                body = "点下方「写笔记」记下一条灵感，之后会出现在这里。",
                updatedTimeLabel = updatedTimeLabel,
                activeNotesCount = 0,
            )
        }

        if (privacyModeEnabled) {
            val count = if (sessionNotes.isNotEmpty()) sessionNotes.size else 1
            val sessionLabel = latestSession?.localDate?.let { "$it · " } ?: ""
            return ReviewWidgetSnapshot(
                sessionId = latestSession?.id,
                primaryNoteId = primaryNote.id,
                privacyModeEnabled = true,
                privacyBadge = privacyBadge,
                headline = "今日待重温（共 $count 篇）",
                body = "${sessionLabel}已启用锁屏/桌面隐私保护，点此打开浮记查看原文。",
                updatedTimeLabel = updatedTimeLabel,
                activeNotesCount = activeNotes.size,
            )
        }

        val title = LocalRetrievalEngine.displayNoteTitle(primaryNote)
        val rawBody = LocalRetrievalEngine.bodyExcludingTitle(primaryNote, title = title)
        val bodyText = rawBody.take(72).ifBlank { "点击打开这条记录详情" }
        return ReviewWidgetSnapshot(
            sessionId = latestSession?.id,
            primaryNoteId = primaryNote.id,
            privacyModeEnabled = false,
            privacyBadge = privacyBadge,
            headline = title,
            body = bodyText,
            updatedTimeLabel = updatedTimeLabel,
            activeNotesCount = activeNotes.size,
        )
    }

    private fun parseStringArray(json: String): List<String> = runCatching {
        MiniJson.parseArray(json).filterIsInstance<String>()
    }.getOrDefault(emptyList())

    private fun parseInstant(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()
}
