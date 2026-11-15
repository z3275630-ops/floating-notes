package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DiscoveryEngineTest {
    private fun note(
        id: String,
        title: String? = null,
        paragraphs: List<String>,
        createdAt: String = "2026-11-10T08:00:00Z",
        updatedAt: String = createdAt,
        deletedAt: String? = null,
        archivedAt: String? = null,
    ): NoteEntity {
        return NoteEntity(
            id = id,
            title = title,
            documentJson = "{}",
            searchText = paragraphs.joinToString("\n"),
            sourceType = "manual",
            sourceUrl = null,
            createdAt = createdAt,
            updatedAt = updatedAt,
            revision = 1L,
            contentHash = "hash-$id",
            archivedAt = archivedAt,
            deletedAt = deletedAt,
            lastDraftId = "draft-$id",
            committedSeq = 1L,
        )
    }

    @Test
    fun tokenizeAndExtractQueryTermsHandlesNaturalChineseQuestion() {
        val terms = LocalRetrievalEngine.extractQueryTerms("以前有哪些关于项目交付的风险和待办？")
        assertTrue(terms.contains("项目"))
        assertTrue(terms.contains("交付"))
        assertTrue(terms.contains("风险"))
        assertTrue(terms.contains("待办"))
        assertFalse(terms.contains("有哪些"))
    }

    @Test
    fun searchEvidenceMergesAdjacentChunksAndFiltersDeletedAndArchived() {
        val longMatch = note(
            id = "n-match",
            title = "项目交付复盘",
            paragraphs = listOf(
                "本周需要重点排查项目交付风险，确认接口联调时间。",
                "接下来要补齐验收待办清单并通知测试同事。".repeat(6),
                "最后一个与风险验收有关的补充段落，用于验证相邻分块合并。",
            ),
        )
        val trashed = note(
            id = "n-trash",
            title = "项目风险（已删除）",
            paragraphs = listOf("项目交付风险早已删除"),
            deletedAt = "2026-11-14T10:00:00Z",
        )
        val archived = note(
            id = "n-arch",
            title = "去年项目归档",
            paragraphs = listOf("归档笔记里的项目交付风险"),
            archivedAt = "2026-11-12T10:00:00Z",
        )
        val all = listOf(longMatch, trashed, archived)
        val chunks = all.flatMap { LocalRetrievalEngine.buildChunks(it) }

        val activeOnly = LocalRetrievalEngine.searchEvidence(
            query = "项目交付的风险和待办有哪些",
            scope = NoteScope(archived = false),
            allNotes = all,
            allChunks = chunks,
            tags = emptyList(),
            noteTagIdsByNote = emptyMap(),
            noteNotebookIdsByNote = emptyMap(),
        )
        assertEquals(listOf("n-match"), activeOnly.hits.map { it.note.id })
        assertTrue(activeOnly.hits.single().mergedExcerpt.contains("风险"))
        assertEquals(1, activeOnly.coverage.eligibleCount)
        assertEquals(1, activeOnly.coverage.indexedCount)

        val withArchived = LocalRetrievalEngine.searchEvidence(
            query = "项目交付风险",
            scope = NoteScope(archived = true),
            allNotes = all,
            allChunks = chunks,
            tags = emptyList(),
            noteTagIdsByNote = emptyMap(),
            noteNotebookIdsByNote = emptyMap(),
        )
        assertEquals(setOf("n-match", "n-arch"), withArchived.hits.mapTo(HashSet()) { it.note.id })
    }

    @Test
    fun relatedNotesExcludesSelfMatchesSubtreeTagsAndLinks() {
        val rootTag = TagEntity("t-root", "", "工作", "工作")
        val childTag = TagEntity("t-child", "t-root", "项目A", "工作/项目a")
        val source = note("n-src", title = "当前记录", paragraphs = listOf("准备架构评审会发言稿"))
        val linked = note("n-linked", title = "架构评审背景", paragraphs = listOf("去年架构讨论纪要"))
        val tagPeer = note("n-tag", title = "项目排期", paragraphs = listOf("里程碑排期确认"))
        val trashedPeer = note("n-del", title = "废稿", paragraphs = listOf("架构评审"), deletedAt = "2026-11-14T12:00:00Z")

        val related = LocalRetrievalEngine.computeRelatedNotes(
            sourceNote = source,
            allNotes = listOf(source, linked, tagPeer, trashedPeer),
            chunksByNote = emptyMap(),
            tags = listOf(rootTag, childTag),
            noteTagIdsByNote = mapOf(
                "n-src" to setOf("t-root"),
                "n-tag" to setOf("t-child"),
            ),
            noteNotebookIdsByNote = emptyMap(),
            links = listOf(NoteLinkEntity("n-src", "b1", "n-linked", 1L)),
        )
        assertFalse(related.any { it.note.id == "n-src" || it.note.id == "n-del" })
        assertEquals(listOf("n-linked", "n-tag"), related.map { it.note.id })
    }

    @Test
    fun dailyReviewIsStablePerDayAndOnThisDayNeverFabricates() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 11, 15)
        val now = today.atTime(10, 0).atZone(zone).toInstant()

        val n1 = note("n-1", paragraphs = listOf("第一条笔记内容"), createdAt = "2026-11-10T08:00:00Z")
        val n2 = note("n-2", paragraphs = listOf("第二条笔记内容"), createdAt = "2026-11-01T08:00:00Z")
        val n3 = note("n-3", paragraphs = listOf("第三条笔记内容"), createdAt = "2026-10-15T08:00:00Z")
        val lastYearSameDay = note("n-past", paragraphs = listOf("去年今天的感想"), createdAt = "2025-11-15T06:00:00Z")
        val notes = listOf(n1, n2, n3, lastYearSameDay)

        val deckA = DiscoveryEngines.selectDailyReviewNoteIds(
            notes = notes,
            noteTagIdsByNote = emptyMap(),
            events = emptyList(),
            config = ReviewConfig(dailyCount = 3),
            localDate = today,
            profileKey = "default",
            round = 1,
            now = now,
            zoneId = zone,
        )
        val deckB = DiscoveryEngines.selectDailyReviewNoteIds(
            notes = notes,
            noteTagIdsByNote = emptyMap(),
            events = emptyList(),
            config = ReviewConfig(dailyCount = 3),
            localDate = today,
            profileKey = "default",
            round = 1,
            now = now,
            zoneId = zone,
        )
        assertEquals(deckA, deckB)

        val onThisDay = DiscoveryEngines.findOnThisDayNotes(notes, today, zone)
        assertEquals(listOf("n-past"), onThisDay.map { it.note.id })
        assertEquals(1, onThisDay.single().yearsAgo)

        val noPastNotes = DiscoveryEngines.findOnThisDayNotes(listOf(n1, n2, n3), today, zone)
        assertTrue(noPastNotes.isEmpty())
    }

    @Test
    fun walkCandidateSupportsCrossTopicLabelAndPrivacyPostcardHidesDetails() {
        val zone = ZoneId.of("Asia/Shanghai")
        val tWork = TagEntity("t-w", "", "工作", "工作")
        val nStart = note("w-start", title = "需求拆解", paragraphs = listOf("整理用户登录流程需求"))
        val nRel = note("w-rel", title = "登录安全方案", paragraphs = listOf("验证用户登录流程和令牌刷新"))
        val nOther = note("w-other", title = "周末做饭", paragraphs = listOf("尝试做番茄牛腩和土豆炖鸡"))

        val crossChoice = DiscoveryEngines.pickNextWalkCandidate(
            currentNote = nStart,
            allNotes = listOf(nStart, nRel, nOther),
            chunksByNote = emptyMap(),
            tags = listOf(tWork),
            noteTagIdsByNote = mapOf("w-start" to setOf("t-w"), "w-rel" to setOf("t-w")),
            noteNotebookIdsByNote = emptyMap(),
            links = emptyList(),
            visitedNoteIds = setOf("w-start"),
            excludedNoteIds = emptySet(),
            seedTagId = null,
            forceCrossDirection = true,
            rollPercent = 80,
        )
        assertNotNull(crossChoice)
        assertEquals("w-other", crossChoice!!.note.id)
        assertEquals("换条小路", crossChoice.reasonLabel)

        val steps = listOf(
            WalkStepEntity("s1", "sess", 0, null, "w-start", "起点", "seed", 0, "灵光一现", "2026-11-15T09:00:00Z"),
            WalkStepEntity("s2", "sess", 1, "s1", "w-other", "换条小路", "cross_topic", 1, null, "2026-11-15T09:02:00Z"),
        )
        val privatePostcard = DiscoveryEngines.buildWalkPostcard(
            steps = steps,
            notesById = mapOf("w-start" to nStart, "w-other" to nOther),
            noteTagsByNote = mapOf("w-start" to listOf("工作")),
            zoneId = zone,
            hideDetailsForPrivacy = true,
        )
        assertFalse(privatePostcard.markdownBody.contains("需求拆解"))
        assertFalse(privatePostcard.markdownBody.contains("番茄牛腩"))
        assertTrue(privatePostcard.markdownBody.contains("灵光一现"))
        assertEquals(2, privatePostcard.stepCount)
    }

    @Test
    fun edgeEchoThrottleRespectsComposingIntervalAndMute() {
        assertFalse(
            DiscoveryEngines.shouldTriggerAutoEcho(
                globalEnabled = true,
                sessionMuted = false,
                isComposing = true,
                draftText = "足够长的写作正文内容",
                lastAutoShownAtMs = 0L,
                nowMs = 40_000L,
            ),
        )
        assertFalse(
            DiscoveryEngines.shouldTriggerAutoEcho(
                globalEnabled = true,
                sessionMuted = false,
                isComposing = false,
                draftText = "足够长的写作正文内容",
                lastAutoShownAtMs = 20_000L,
                nowMs = 40_000L, // 20s < 30s interval
            ),
        )
        assertTrue(
            DiscoveryEngines.shouldTriggerAutoEcho(
                globalEnabled = true,
                sessionMuted = false,
                isComposing = false,
                draftText = "足够长的写作正文内容",
                lastAutoShownAtMs = 5_000L,
                nowMs = 40_000L, // 35s >= 30s interval
            ),
        )
        assertFalse(
            DiscoveryEngines.shouldTriggerAutoEcho(
                globalEnabled = true,
                sessionMuted = true,
                isComposing = false,
                draftText = "足够长的写作正文内容",
                lastAutoShownAtMs = 0L,
                nowMs = 40_000L,
            ),
        )
    }
}
