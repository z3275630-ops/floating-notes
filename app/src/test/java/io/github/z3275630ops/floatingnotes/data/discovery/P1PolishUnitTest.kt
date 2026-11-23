package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange
import io.github.z3275630ops.floatingnotes.data.agent.LocalAgentEngine
import io.github.z3275630ops.floatingnotes.data.backup.BackupCodec
import io.github.z3275630ops.floatingnotes.data.backup.BackupConstants
import io.github.z3275630ops.floatingnotes.data.backup.BackupDraftRecord
import io.github.z3275630ops.floatingnotes.data.backup.BackupNoteRecord
import io.github.z3275630ops.floatingnotes.data.backup.BackupSnapshot
import io.github.z3275630ops.floatingnotes.data.backup.BackupValidationException
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiff
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderEntity
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskItemProjection
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskUrgencyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class P1PolishUnitTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun note(
        id: String,
        title: String? = null,
        paragraphs: List<String> = emptyList(),
        todos: List<Pair<String, Boolean>> = emptyList(),
        aiPolicy: String = "inherit",
        revision: Long = 1L,
        createdAt: String = "2026-11-10T08:00:00Z",
        updatedAt: String = createdAt,
        deletedAt: String? = null,
        archivedAt: String? = null,
    ): NoteEntity {
        val blocks = buildList<Map<String, Any?>> {
            paragraphs.forEachIndexed { idx, text ->
                add(linkedMapOf("id" to "p-$id-$idx", "type" to "paragraph", "text" to text))
            }
            todos.forEachIndexed { idx, (text, checked) ->
                add(linkedMapOf("id" to "c-$id-$idx", "type" to "checkbox", "text" to text, "checked" to checked))
            }
        }
        val docJson = MiniJson.encode(linkedMapOf("document_schema_version" to 1, "blocks" to blocks))
        val searchText = (paragraphs + todos.map { it.first }).joinToString("\n")
        return NoteEntity(
            id = id,
            title = title,
            documentJson = docJson,
            searchText = searchText,
            authorType = "user",
            sourceType = "manual",
            sourceUrl = null,
            aiPolicy = aiPolicy,
            createdAt = createdAt,
            updatedAt = updatedAt,
            revision = revision,
            contentHash = "hash-$id-$revision",
            archivedAt = archivedAt,
            deletedAt = deletedAt,
            lastDraftId = "draft-$id",
            committedSeq = revision,
        )
    }

    @Test
    fun versionDiffParseBlocksRejectsDuplicateBlockIds() {
        val duplicateDoc = MiniJson.encode(
            linkedMapOf(
                "document_schema_version" to 1,
                "blocks" to listOf(
                    linkedMapOf("id" to "dup-1", "type" to "paragraph", "text" to "第一段"),
                    linkedMapOf("id" to "dup-1", "type" to "paragraph", "text" to "第二段"),
                ),
            ),
        )
        try {
            VersionDiff.parseBlocks(duplicateDoc)
            fail("Expected BackupValidationException for duplicate block IDs")
        } catch (_: BackupValidationException) {
            // Expected P1-03 validation rejection.
        }
    }

    @Test
    fun polyphonePhrasesAndLongBlockChunkSplittingWorkTogether() {
        val bankSyllables = ChinesePinyinTable.toPinyinParts("银行对账单").syllables
        assertTrue("Expected 'yin' in 银行: $bankSyllables", "yin" in bankSyllables)
        assertTrue("Expected 'hang' in 银行: $bankSyllables", "hang" in bankSyllables)

        val growthSyllables = ChinesePinyinTable.toPinyinParts("快速成长").syllables
        assertTrue("Expected 'cheng' in 成长: $growthSyllables", "cheng" in growthSyllables)
        assertTrue("Expected 'zhang' in 成长: $growthSyllables", "zhang" in growthSyllables)

        val fuzzy = LocalFuzzyMatcher.matchFuzzyText("yinhang", "今天去银行办理对账")
        assertTrue("Expected pinyin fuzzy match for polyphone phrase 银行", fuzzy.matched)

        val longParagraph = buildString {
            append("开篇先记录今天的需求沟通与系统设计重点。")
            repeat(8) { idx ->
                append("第${idx + 1}阶段先保证本地检索与离线稳定性，持续核对边界条件与性能基线。")
            }
            append("尾部关键暗语火星探测验收清单。")
        }
        assertTrue(longParagraph.length > 260)
        val longNote = note(id = "n-long", title = "长段落分块测试", paragraphs = listOf(longParagraph))
        val chunks = LocalRetrievalEngine.buildChunks(longNote)
        assertTrue("Expected >1 chunk for >220 char block, got ${chunks.size}", chunks.size > 1)
        assertTrue("Tail content must be preserved in later chunks", chunks.any { it.excerpt.contains("火星探测验收清单") })
    }

    @Test
    fun fsrsDeduplicatesSameSessionEventsAndReviewPrefersUnseenToday() {
        val n1 = note(id = "n1", title = "旧卡片1", paragraphs = listOf("复盘思考卡片一"), createdAt = "2026-10-01T08:00:00Z")
        val n2 = note(id = "n2", title = "旧卡片2", paragraphs = listOf("复盘思考卡片二"), createdAt = "2026-10-02T08:00:00Z")
        val n3 = note(id = "n3", title = "新卡", paragraphs = listOf("还没看过的复盘卡片"), createdAt = "2026-10-03T08:00:00Z")
        val now = Instant.parse("2026-11-23T08:00:00Z")
        val zone = ZoneId.of("UTC")

        // Same session has multiple "seen" events plus "annotated" for n1: must collapse without inflating reps.
        val events = listOf(
            ReviewEventEntity("e1", "s1", "n1", "seen", null, "2026-11-20T08:00:00Z"),
            ReviewEventEntity("e2", "s1", "n1", "annotated", null, "2026-11-20T08:01:00Z"),
            ReviewEventEntity("e3", "s1", "n1", "seen", null, "2026-11-20T08:02:00Z"),
        )
        val states = FsrsReviewScheduler.computeNoteStates(listOf(n1, n2, n3), events, now, zone)
        assertEquals(1, states.getValue("n1").reps)
        assertEquals(5.0, states.getValue("n1").stabilityDays, 1e-6)

        val selected = DiscoveryEngines.selectDailyReviewNoteIds(
            notes = listOf(n1, n2, n3),
            noteTagIdsByNote = emptyMap(),
            events = emptyList(),
            config = ReviewConfig(dailyCount = 1),
            localDate = LocalDate.of(2026, 11, 23),
            profileKey = "default",
            round = 2,
            now = now,
            zoneId = zone,
            alreadyShownNoteIds = setOf("n1", "n2"),
        )
        assertEquals(listOf("n3"), selected)
    }

    @Test
    fun textIntelligenceHandlesDecimalsMaxCharsExclusionsAndCancellation() {
        val sentences = LocalTextIntelligence.splitSentences("Version 3.14 ships cleanly. Second sentence follows.")
        assertEquals(2, sentences.size)
        assertTrue(sentences[0].contains("3.14"))

        val longText = "第一句摘要记录了项目启动背景和最初的问题设定。" +
            "第二句非常详细地展开说明架构拆分方案与离线索引构建策略以及后续测试安排，超过限制长度。" +
            "第三句总结本周需要优先对齐的验收标准。"
        val summary = LocalTextIntelligence.extractKeySentences(longText, maxSentences = 3, maxChars = 60)
        assertTrue(summary.sentences.isNotEmpty())
        assertTrue("Returned sentences exceeded maxChars=60", summary.sentences.sumOf { it.length } <= 60)

        val sharedBody = listOf("产品发布准备清单：核对版本号、更新变更日志、检查离线备份导出流程、确认真实设备兼容性与通知权限提示逻辑完全通过验收。")
        val allowedA = note(id = "d1", title = "发布清单 A", paragraphs = sharedBody, aiPolicy = "inherit")
        val allowedB = note(id = "d2", title = "发布清单 B", paragraphs = sharedBody, aiPolicy = "allow")
        val excluded = note(id = "d3", title = "私密发布清单", paragraphs = sharedBody, aiPolicy = "exclude")

        val groups = LocalTextIntelligence.findDuplicateGroups(listOf(allowedA, allowedB, excluded))
        assertEquals(1, groups.size)
        val groupIds = setOf(groups[0].primaryNote.id) + groups[0].duplicateNotes.map { it.id }
        assertEquals(setOf("d1", "d2"), groupIds)
        assertFalse("Excluded note must not appear in duplicate groups", "d3" in groupIds)

        var checks = 0
        try {
            LocalTextIntelligence.findDuplicateGroups(listOf(allowedA, allowedB)) {
                checks += 1
                throw IllegalStateException("cancelled")
            }
            fail("Expected checkCancelled exception to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("cancelled", expected.message)
            assertTrue(checks >= 1)
        }
    }

    @Test
    fun tagRecommendationExcludesPrivacyNotesAndBridgeUsesPassedCardRevisions() {
        val secretTag = TagEntity("t-secret", "", "机密体系", tagNameKey("机密体系"))
        val baseTag = TagEntity("t-base", "", "基础设施", tagNameKey("基础设施"))
        val excludedSource = note(
            id = "secret",
            title = "隔离文档",
            paragraphs = listOf("微服务 索引 分块 隔离检索 高并发"),
            aiPolicy = "exclude",
        )
        val excludedRecs = LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = "微服务 索引 分块",
            currentNoteId = null,
            attachedTagIds = setOf("t-base"),
            allNotes = listOf(excludedSource),
            allChunks = LocalRetrievalEngine.buildChunks(excludedSource),
            tags = listOf(secretTag, baseTag),
            noteTagIdsByNote = mapOf("secret" to setOf("t-secret", "t-base")),
        )
        assertTrue("aiPolicy=exclude notes must not leak tags via kNN or co-occurrence: $excludedRecs", excludedRecs.isEmpty())

        val allowedSource = excludedSource.copy(aiPolicy = "inherit")
        val allowedRecs = LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = "微服务 索引 分块",
            currentNoteId = null,
            attachedTagIds = setOf("t-base"),
            allNotes = listOf(allowedSource),
            allChunks = LocalRetrievalEngine.buildChunks(allowedSource),
            tags = listOf(secretTag, baseTag),
            noteTagIdsByNote = mapOf("secret" to setOf("t-secret", "t-base")),
        )
        assertTrue("Control check: allowed note recommends co-occurring tag", allowedRecs.any { it.tag.id == "t-secret" })

        val freshLeft = note(
            id = "left",
            title = "新版左侧标题",
            paragraphs = listOf("新版左侧强调知识图谱与本地搜索设计"),
            revision = 2L,
        )
        val freshRight = note(
            id = "right",
            title = "新版右侧标题",
            paragraphs = listOf("新版右侧讨论图谱连接以及双卡灵感对撞"),
            revision = 2L,
        )
        val staleCorpus = listOf(
            note(id = "left", title = "旧版", paragraphs = listOf("完全无关的旧句子"), revision = 1L),
            note(id = "right", title = "旧版", paragraphs = listOf("完全无关的旧文本"), revision = 1L),
        )
        val (_, _, overlap) = LocalTagAndGraphEngine.computeBridgeInsights(
            left = freshLeft,
            right = freshRight,
            leftTags = emptyList(),
            rightTags = emptyList(),
            allNotes = staleCorpus,
            noteTagIdsByNote = emptyMap(),
            tagsById = emptyMap(),
        )
        assertTrue("Passed fresh pair revisions must drive bridge overlap rather than stale corpus", overlap > 0.0)
    }

    @Test
    fun localAgentInspectsRealReminderStatesAndDuplicateSnapshots() {
        val now = Instant.parse("2026-11-23T10:00:00Z")
        val zone = ZoneId.of("UTC")
        val noteWithReminder = note(
            id = "n-todo",
            title = "提审任务",
            todos = listOf("发送发版说明给测试同学" to false),
            createdAt = "2026-11-23T08:00:00Z",
        )
        val missedReminder = ReminderEntity(
            id = "rem-1",
            noteId = "n-todo",
            blockId = "c-n-todo-0",
            occurrenceId = null,
            zoneId = "UTC",
            localDate = "2026-11-20",
            localTime = "09:00",
            triggerInstantMs = Instant.parse("2026-11-20T09:00:00Z").toEpochMilli(),
            policy = ReminderPolicy.FOLLOW_LOCAL.name,
            status = ReminderStatus.MISSED.name,
            recurrence = RecurrenceRule.NONE.name,
            updatedAt = "2026-11-20T09:00:00Z",
        )
        val outcome = LocalAgentEngine.analyze(
            skill = AgentSkill.TodoTriage,
            prompt = AgentSkill.TodoTriage.defaultPrompt,
            notes = listOf(noteWithReminder),
            chunks = LocalRetrievalEngine.buildChunks(noteWithReminder),
            tags = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            links = emptyList(),
            scope = NoteScope(aiOnly = true),
            timeRange = AgentTimeRange.All,
            now = now,
            zoneId = zone,
            reminders = listOf(missedReminder),
        )
        assertEquals("completed", outcome.status)
        assertTrue(
            "Expected reminder date detection from ReminderEntity: ${outcome.result.observations}",
            outcome.result.observations.any { it.contains("1 项设定了明确日期") },
        )
        assertTrue(
            "Expected missed overdue reminder urgency from ReminderEntity: ${outcome.result.observations}",
            outcome.result.observations.any { it.contains("1 项已逾期") && it.contains("错过提醒") },
        )

        val dupBody = listOf("本地检索与双卡对撞算法核对清单：保持离线可验证、确保测试全量通过并且覆盖边界条件。")
        val dup1 = note(id = "dup-1", title = "核对 1", paragraphs = dupBody, updatedAt = "2026-11-23T09:00:00Z")
        val dup2 = note(id = "dup-2", title = "核对 2", paragraphs = dupBody, updatedAt = "2026-11-22T09:00:00Z")
        val dupOutcome = LocalAgentEngine.analyze(
            skill = AgentSkill.DuplicateCleanup,
            prompt = AgentSkill.DuplicateCleanup.defaultPrompt,
            notes = listOf(dup1, dup2),
            chunks = LocalRetrievalEngine.buildChunks(dup1) + LocalRetrievalEngine.buildChunks(dup2),
            tags = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            links = emptyList(),
            scope = NoteScope(aiOnly = true),
            timeRange = AgentTimeRange.All,
            now = now,
            zoneId = zone,
        )
        assertEquals("completed", dupOutcome.status)
        assertEquals(AgentTimeRange.All.name, dupOutcome.result.timeRangeName)
        assertEquals(1, dupOutcome.result.duplicateGroupSnapshots.size)
        val snap = dupOutcome.result.duplicateGroupSnapshots.single()
        assertEquals(setOf("dup-1", "dup-2"), setOf(snap.primaryNoteId) + snap.duplicateNoteIds)
    }

    @Test
    fun urgencyStaleReasonAndDualCollisionPairRespectConstraints() {
        val projection = TaskItemProjection(
            noteId = "n-stale",
            blockId = "c-1",
            text = "整理旧档案目录",
            checked = false,
            dueDate = null,
            occurrenceId = null,
            noteTitle = "旧档案",
            notePreview = "整理旧档案目录",
            noteArchived = false,
            noteUpdatedAt = "2026-11-03T08:00:00Z",
            reminderId = null,
            reminderZoneId = null,
            reminderLocalDate = null,
            reminderLocalTime = null,
            triggerInstantMs = null,
            policy = ReminderPolicy.FOLLOW_LOCAL,
            reminderStatus = null,
            recurrence = RecurrenceRule.NONE,
            snoozedUntilMs = null,
            missedSummarized = false,
        )
        val score = TaskUrgencyEngine.evaluate(projection, LocalDate.of(2026, 11, 23), ZoneId.of("UTC"))
        assertEquals("搁置 20 天", score.badgeLabel)
        assertEquals("所在笔记已 20 天未更新", score.reason)

        val nAllowed = note(id = "a1", title = "允许卡片", paragraphs = listOf("普通可见卡片文本"))
        val nPrivate = note(id = "p1", title = "隐私卡片", paragraphs = listOf("不允许参与发现分析"), aiPolicy = "exclude")
        val nHidden = note(id = "h1", title = "隐藏卡片", paragraphs = listOf("用户手动从发现里隐藏"))

        val (pair, eligibleCount) = DiscoveryEngines.pickDualCollisionPair(
            notes = listOf(nAllowed, nPrivate, nHidden),
            noteTagIdsByNote = emptyMap(),
            tagsById = emptyMap(),
            random = Random(1),
            excludedNoteIds = setOf("h1"),
        )
        assertEquals(1, eligibleCount)
        assertEquals(null, pair)
    }

    @Test
    fun backupCodecIncludesUninsertedAudioDefaultExclusionAndStreamsManyDrafts() {
        assertTrue(BackupConstants.DEFAULT_EXCLUSIONS.contains("uninserted_audio_transcripts"))

        val docJson = MiniJson.encode(
            linkedMapOf(
                "document_schema_version" to 1,
                "blocks" to listOf(
                    linkedMapOf("id" to "b1", "type" to "paragraph", "text" to "基线正文内容"),
                ),
            ),
        )
        val drafts = (0 until 500).map { idx ->
            BackupDraftRecord(
                id = "draft-$idx",
                slot = "capture:draft-$idx",
                noteId = "note-$idx",
                baseRevision = 0L,
                documentJson = docJson,
                editSeq = 1L,
                updatedAt = "2026-11-23T08:00:00.000Z",
                sourceType = "manual",
                sourceUrl = null,
            )
        }
        val snapshot = BackupSnapshot(
            exportedAt = "2026-11-23T08:00:00.000Z",
            appVersion = "0.2.7-p1-polish.1",
            dbSchemaVersion = 7,
            notes = listOf(
                BackupNoteRecord(
                    id = "note-0",
                    title = "基线笔记",
                    documentJson = docJson,
                    searchText = "基线笔记",
                    authorType = "user",
                    sourceType = "manual",
                    sourceUrl = null,
                    aiPolicy = "inherit",
                    createdAt = "2026-11-23T08:00:00.000Z",
                    updatedAt = "2026-11-23T08:00:00.000Z",
                    revision = 1L,
                    serverRevision = null,
                    contentHash = "h-0",
                    archivedAt = null,
                    deletedAt = null,
                    pinned = false,
                    lastDraftId = "draft-0",
                    committedSeq = 1L,
                    mediaMask = 0,
                ),
            ),
            versions = emptyList(),
            drafts = drafts,
            tags = emptyList(),
            notebooks = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            noteLinks = emptyList(),
            savedSearches = emptyList(),
            reminders = emptyList(),
            taskOccurrences = emptyList(),
            attachments = emptyList(),
        )

        val zip = File(temp.root, "stream-drafts.zip")
        val manifest = BackupCodec.writeBackupZip(snapshot, zip) { null }
        assertNotNull(manifest)
        val parsed = BackupCodec.readAndValidateArchive(zip)
        assertEquals(500, parsed.snapshot.drafts.size)
        assertEquals(500, parsed.snapshot.drafts.mapTo(HashSet()) { it.id }.size)
        assertTrue(parsed.snapshot.drafts.any { it.id == "draft-499" })
    }
}
