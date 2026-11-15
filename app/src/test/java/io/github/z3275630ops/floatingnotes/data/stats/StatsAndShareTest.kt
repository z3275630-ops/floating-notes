package io.github.z3275630ops.floatingnotes.data.stats

import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewSessionEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardOptions
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardPaginator
import io.github.z3275630ops.floatingnotes.feature.widget.FloatingNotesWidgetStateBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class StatsAndShareTest {

    private fun note(
        id: String,
        title: String? = null,
        paragraphs: List<String> = listOf("正文 $id"),
        authorType: String = "user",
        sourceType: String = "manual",
        sourceUrl: String? = null,
        createdAt: String = "2026-11-15T04:00:00Z",
        updatedAt: String = createdAt,
        deletedAt: String? = null,
        extraBlocks: List<Map<String, Any?>> = emptyList(),
    ): NoteEntity {
        val blocks = paragraphs.mapIndexed { index, text ->
            linkedMapOf<String, Any?>(
                "id" to "b-$id-$index",
                "type" to "paragraph",
                "text" to text,
            )
        } + extraBlocks
        val docJson = MiniJson.encode(linkedMapOf("document_schema_version" to 1, "blocks" to blocks))
        return NoteEntity(
            id = id,
            title = title,
            documentJson = docJson,
            searchText = paragraphs.joinToString("\n"),
            authorType = authorType,
            sourceType = sourceType,
            sourceUrl = sourceUrl,
            aiPolicy = "inherit",
            createdAt = createdAt,
            updatedAt = updatedAt,
            revision = 1L,
            contentHash = "h-$id",
            archivedAt = null,
            deletedAt = deletedAt,
            lastDraftId = "d-$id",
            committedSeq = 1L,
        )
    }

    @Test
    fun statsEngineSeparatesOriginScopesExcludesDeletedAndMatchesDrilldownCounts() {
        val manualToday = note("m1", paragraphs = listOf("手写灵感"), createdAt = "2026-11-15T06:00:00Z")
        val manualTrash = note(
            id = "m-del",
            paragraphs = listOf("已删草稿"),
            createdAt = "2026-11-15T07:00:00Z",
            deletedAt = "2026-11-15T08:00:00Z",
        )
        val importedNote = note(
            id = "imp1",
            paragraphs = listOf("Markdown 导入"),
            sourceType = "import_markdown",
            createdAt = "2026-11-15T09:00:00Z",
        )
        val aiNote = note(
            id = "ai1",
            paragraphs = listOf("Agent 复盘"),
            authorType = "assistant",
            sourceType = "agent_local",
            createdAt = "2026-11-15T10:00:00Z",
        )

        val tags = listOf(TagEntity(id = "t1", parentId = "", name = "复盘", nameKey = "复盘"))
        val refs = listOf(
            NoteTagEntity(noteId = "m1", tagId = "t1"),
            NoteTagEntity(noteId = "ai1", tagId = "t1"),
        )
        val now = Instant.parse("2026-11-15T12:00:00Z")
        val utc = ZoneId.of("UTC")

        // Q039 + Q040: Manual scope defaults to personal writing only, zero baseline has null deltaPercent
        val manualSnap = StatsEngine.computeSnapshot(
            allNotes = listOf(manualToday, manualTrash, importedNote, aiNote),
            tagRefs = refs,
            tagsById = tags.associateBy { it.id },
            originScope = StatsOriginScope.Manual,
            periodMode = StatsPeriodMode.HeatmapWeeks,
            focusedMonth = YearMonth.of(2026, 11),
            zoneId = utc,
            now = now,
        )
        assertEquals(3, manualSnap.totalActiveNotes)
        assertEquals(1, manualSnap.manualCount)
        assertEquals(1, manualSnap.importedCount)
        assertEquals(1, manualSnap.aiGeneratedCount)
        assertEquals(1, manualSnap.scopedTotalCount)
        assertNull(manualSnap.currentMonthComparison.deltaPercent)
        assertTrue(manualSnap.currentMonthComparison.formatSummary("本月").contains("上期基数为 0"))

        val today = LocalDate.of(2026, 11, 15)
        val cellManual = manualSnap.heatmapCells.first { it.date == today }
        assertEquals(1, cellManual.count)
        assertEquals(listOf("m1"), manualSnap.notesForDate(today).map { it.note.id })

        // All scope combines active notes while still excluding soft-deleted m-del (Q038)
        val allSnap = StatsEngine.computeSnapshot(
            allNotes = listOf(manualToday, manualTrash, importedNote, aiNote),
            tagRefs = refs,
            tagsById = tags.associateBy { it.id },
            originScope = StatsOriginScope.All,
            periodMode = StatsPeriodMode.HeatmapWeeks,
            focusedMonth = YearMonth.of(2026, 11),
            zoneId = utc,
            now = now,
        )
        val cellAll = allSnap.heatmapCells.first { it.date == today }
        assertEquals(3, cellAll.count)
        assertEquals(3, allSnap.notesForDate(today).size)
        assertFalse(allSnap.notesForDate(today).any { it.note.id == "m-del" })
    }

    @Test
    fun statsEngineTimezoneShiftMovesDayBucketCorrectly() {
        // 2026-11-15T18:30:00Z is 2026-11-15 in UTC, but 2026-11-16 02:30 in Asia/Shanghai (+08:00)
        val lateUtc = note("tz1", paragraphs = listOf("跨日笔记"), createdAt = "2026-11-15T18:30:00Z")
        val now = Instant.parse("2026-11-16T04:00:00Z")

        val utcSnap = StatsEngine.computeSnapshot(
            allNotes = listOf(lateUtc),
            tagRefs = emptyList(),
            tagsById = emptyMap(),
            originScope = StatsOriginScope.Manual,
            periodMode = StatsPeriodMode.HeatmapWeeks,
            focusedMonth = YearMonth.of(2026, 11),
            zoneId = ZoneId.of("UTC"),
            now = now,
        )
        assertEquals(1, utcSnap.notesForDate(LocalDate.of(2026, 11, 15)).size)
        assertEquals(0, utcSnap.notesForDate(LocalDate.of(2026, 11, 16)).size)

        val shanghaiSnap = StatsEngine.computeSnapshot(
            allNotes = listOf(lateUtc),
            tagRefs = emptyList(),
            tagsById = emptyMap(),
            originScope = StatsOriginScope.Manual,
            periodMode = StatsPeriodMode.HeatmapWeeks,
            focusedMonth = YearMonth.of(2026, 11),
            zoneId = ZoneId.of("Asia/Shanghai"),
            now = now,
        )
        assertEquals(0, shanghaiSnap.notesForDate(LocalDate.of(2026, 11, 15)).size)
        assertEquals(1, shanghaiSnap.notesForDate(LocalDate.of(2026, 11, 16)).size)
    }

    @Test
    fun shareCardPaginatorSplitsLongNoteNoticesUnsupportedMediaAndHidesSourceByDefault() {
        val longParagraphs = (1..18).map { idx ->
            "第 $idx 段复盘：我们正在验证长文自动分页与隐私元数据开关，确保每一页卡片版式完整且来源标记可控。".repeat(2)
        }
        val audioBlock = linkedMapOf<String, Any?>(
            "id" to "att-audio-1",
            "type" to "audio",
            "attachment_id" to "a-1",
        )
        val longNote = note(
            id = "long-1",
            title = "季度深度回顾",
            paragraphs = longParagraphs,
            sourceType = "share",
            sourceUrl = "https://private.internal.example/doc/42",
            extraBlocks = listOf(audioBlock),
        )

        val defaultPrepared = ShareCardPaginator.buildPreparedContent(
            note = longNote,
            tagNames = listOf("产品/思考", "M4"),
            options = ShareCardOptions(),
            zoneId = ZoneId.of("UTC"),
        )
        assertTrue(defaultPrepared.pages.size >= 2)
        assertEquals(1, defaultPrepared.unsupportedMediaCount)
        assertTrue(defaultPrepared.pages.all { it.sourceMetaLabel == null })
        assertEquals("季度深度回顾", defaultPrepared.pages.first().headerTitle)

        val withSourcePrepared = ShareCardPaginator.buildPreparedContent(
            note = longNote,
            tagNames = listOf("产品/思考"),
            options = ShareCardOptions(includeSourceMetadata = true),
            zoneId = ZoneId.of("UTC"),
        )
        assertTrue(
            withSourcePrepared.pages.first().sourceMetaLabel?.contains("private.internal.example") == true,
        )
    }

    @Test
    fun widgetStateBuilderRespectsPrivacyMaskAndExcludesTrashedNotes() {
        val active = note("w1", title = "私密日记", paragraphs = listOf("仅自己可见的正文细节"), createdAt = "2026-11-15T08:00:00Z")
        val trashed = note(
            id = "w-trash",
            title = "已删废纸",
            paragraphs = listOf("不应该出现"),
            createdAt = "2026-11-15T09:00:00Z",
            deletedAt = "2026-11-15T10:00:00Z",
        )
        val session = ReviewSessionEntity(
            id = "rev-session-1",
            localDate = "2026-11-15",
            profileKey = "daily",
            configHash = "cfg-1",
            round = 1,
            cardIdsJson = MiniJson.encode(listOf("w-trash", "w1")),
            currentIndex = 0,
            createdAt = "2026-11-15T11:30:00Z",
            updatedAt = "2026-11-15T11:30:00Z",
        )

        val masked = FloatingNotesWidgetStateBuilder.buildReviewSnapshot(
            latestSession = session,
            notes = listOf(active, trashed),
            privacyModeEnabled = true,
            zoneId = ZoneId.of("UTC"),
            now = Instant.parse("2026-11-15T12:00:00Z"),
        )
        assertEquals("rev-session-1", masked.sessionId)
        assertEquals("w1", masked.primaryNoteId)
        assertTrue(masked.privacyModeEnabled)
        assertFalse(masked.headline.contains("私密日记"))
        assertFalse(masked.body.contains("仅自己可见的正文细节"))
        assertTrue(masked.updatedTimeLabel.contains("11/15 11:30"))

        val preview = FloatingNotesWidgetStateBuilder.buildReviewSnapshot(
            latestSession = session,
            notes = listOf(active, trashed),
            privacyModeEnabled = false,
            zoneId = ZoneId.of("UTC"),
            now = Instant.parse("2026-11-15T12:00:00Z"),
        )
        assertEquals("私密日记", preview.headline)
        assertTrue(preview.body.contains("仅自己可见的正文细节"))
        assertEquals("w1", preview.primaryNoteId)
    }
}
