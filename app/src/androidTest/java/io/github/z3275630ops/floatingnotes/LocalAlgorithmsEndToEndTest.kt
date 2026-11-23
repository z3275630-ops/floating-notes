package io.github.z3275630ops.floatingnotes

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionCommitResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentRepository
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange
import io.github.z3275630ops.floatingnotes.data.agent.LocalAgentEngine
import io.github.z3275630ops.floatingnotes.data.discovery.DailyReviewSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.DuplicateNoteGroup
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.discovery.LocalTextIntelligence
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewCardModel
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewConfig
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewEventEntity
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewSessionEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
import io.github.z3275630ops.floatingnotes.data.local.TaskPreferences
import io.github.z3275630ops.floatingnotes.feature.agent.AgentUiState
import io.github.z3275630ops.floatingnotes.feature.agent.AgentWorkspace
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreScreen
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreUiState
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderCapability
import io.github.z3275630ops.floatingnotes.feature.tasks.TasksScreen
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalAlgorithmsEndToEndTest {

    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Env(
        val db: NotesDatabase,
        val notes: NotesRepository,
        val org: OrganizationRepository,
        val search: SearchRepository,
        val discovery: DiscoveryRepository,
        val agent: AgentRepository,
    )

    private suspend fun withTempEnv(block: suspend (Env) -> Unit) {
        val db = Room.databaseBuilder(
            appContext,
            NotesDatabase::class.java,
            File(temp.root, "e2e-${newId()}.db").absolutePath,
        ).build()
        val prefs = ExplorePreferences(appContext, prefsName = "e2e_explore_${newId()}")
        val notes = NotesRepository(db)
        val org = OrganizationRepository(db)
        val search = SearchRepository(db)
        val discovery = DiscoveryRepository(db, notes, prefs)
        val agent = AgentRepository(db, notes, org, discovery)
        try {
            block(Env(db, notes, org, search, discovery, agent))
        } finally {
            db.close()
        }
    }

    private fun buildNoteEntity(
        id: String,
        title: String? = null,
        body: String,
        createdAt: String,
        updatedAt: String = createdAt,
        sourceType: String = "manual",
    ): NoteEntity = NoteEntity(
        id = id,
        title = title,
        documentJson = NoteDocument(listOf(NoteBlock.paragraph(body))).encode(),
        searchText = body,
        authorType = "user",
        sourceType = sourceType,
        sourceUrl = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = 1L,
        contentHash = "h-$id",
        lastDraftId = "d-$id",
        committedSeq = 1L,
    )

    private suspend fun commitTextNote(
        notes: NotesRepository,
        paragraphs: List<String>,
    ): NoteEntity {
        val blocks = paragraphs.map { NoteBlock.paragraph(it) }
        val draft = notes.newDraft("e2e-${newId()}").copy(
            documentJson = NoteDocument(blocks).encode(),
            editSeq = 1L,
        )
        return notes.commit(draft)
    }

    @Test
    fun fsrsDailyReviewOrdersOverdueBeforeNeverSeenAndJustSeenAndDedupesSeen() = runBlocking {
        withTempEnv { env ->
            val zone = ZoneId.of("Asia/Shanghai")
            val now = Instant.parse("2026-11-23T08:00:00Z")
            val longBody = (
                "第一句阐述系统反馈延迟为什么会让复杂项目陷入震荡，因此需要建立清晰的观测节点与周度校准机制。" +
                    "第二句强调核心结论：把长链路拆成可观察的小步验证，能大幅降低返工成本与决策风险，同时让团队更快对齐关键路径。" +
                    "第三句记录今天的团队对齐情况，大家确认了先做关键路径基线测试并补充自动化回归验证。" +
                    "第四句补充下周计划与硬件实验室真机兼容性验收安排。"
                )

            val overdueAnnotated = buildNoteEntity(
                id = "n-overdue-ann",
                title = "批注后到期卡片",
                body = longBody,
                createdAt = "2026-11-01T08:00:00Z",
            )
            val neverSeenOld = buildNoteEntity(
                id = "n-never-seen",
                title = "半月前未重温卡片",
                body = "半个月前记录的系统思考要点，尚未复盘。",
                createdAt = "2026-11-03T08:00:00Z",
            )
            val justSeen = buildNoteEntity(
                id = "n-just-seen",
                title = "昨天刚看过的卡片",
                body = "昨天刚刚浏览过的读书摘录，记忆留存尚高。",
                createdAt = "2026-11-01T08:00:00Z",
            )
            env.db.notes().insertNotes(listOf(overdueAnnotated, neverSeenOld, justSeen))

            env.db.discovery().upsertReviewEvent(
                ReviewEventEntity(
                    id = "ev-ann-1",
                    sessionId = "prev-session",
                    noteId = overdueAnnotated.id,
                    action = "annotated",
                    untilIso = null,
                    createdAt = "2026-11-05T08:00:00Z",
                ),
            )
            env.db.discovery().upsertReviewEvent(
                ReviewEventEntity(
                    id = "ev-seen-1",
                    sessionId = "prev-session",
                    noteId = justSeen.id,
                    action = "seen",
                    untilIso = null,
                    createdAt = "2026-11-22T08:00:00Z",
                ),
            )

            val snapshot = env.discovery.loadOrCreateDailyReview(now = now, zoneId = zone)
            assertEquals(
                listOf("n-overdue-ann", "n-never-seen", "n-just-seen"),
                snapshot.cards.map { it.note.id },
            )
            assertTrue(snapshot.cards.all { it.scheduleReason.isNotBlank() })
            assertTrue(snapshot.cards[0].scheduleReason.contains("批注巩固到期"))
            assertTrue(snapshot.cards[0].keySentences.isNotEmpty())
            assertTrue(snapshot.cards[1].scheduleReason.contains("首次重温"))
            assertTrue(snapshot.cards[2].scheduleReason.contains("提前巩固"))

            env.discovery.markReviewCardSeen(snapshot.session.id, neverSeenOld.id, now = now)
            env.discovery.markReviewCardSeen(snapshot.session.id, neverSeenOld.id, now = now.plusSeconds(10))
            val seenEvents = env.db.discovery().allReviewEvents().filter {
                it.sessionId == snapshot.session.id &&
                    it.noteId == neverSeenOld.id &&
                    it.action == "seen"
            }
            assertEquals(1, seenEvents.size)
        }
    }

    @Test
    fun pinyinAndTypoRetrievalWorkInSearchFindAndObserveSmartRespectingScope() = runBlocking {
        withTempEnv { env ->
            val workTagId = env.org.createTag("工作")
            val lifeTagId = env.org.createTag("生活")

            val bookNote = commitTextNote(
                env.notes,
                paragraphs = listOf("读书笔记", "读完《系统之美》后写的读书笔记，记录长链路反馈延迟。"),
            )
            env.org.tagNote(bookNote.id, workTagId, attach = true)

            val projNote = commitTextNote(
                env.notes,
                paragraphs = listOf("项目交付清单", "准备下周二的项目交付文档与上线检查清单，以及系统 architecture 复核。"),
            )
            env.org.tagNote(projNote.id, workTagId, attach = true)

            val archivedBookNote = commitTextNote(
                env.notes,
                paragraphs = listOf("归档读书笔记", "这份已归档的读书笔记只应在包含归档时被搜到。"),
            )
            env.org.tagNote(archivedBookNote.id, workTagId, attach = true)
            env.notes.setArchived(archivedBookNote.id, true)

            val findPinyin = env.discovery.searchFind("dsbj", NoteScope())
            assertTrue(findPinyin.hits.any { it.note.id == bookNote.id })
            assertFalse(findPinyin.hits.any { it.note.id == archivedBookNote.id })
            assertTrue(findPinyin.hits.first { it.note.id == bookNote.id }.reasons.any { it.contains("拼音") })

            val findTypo = env.discovery.searchFind("项木交付", NoteScope())
            assertTrue(findTypo.hits.any { it.note.id == projNote.id })

            val findPureEditTypo = env.discovery.searchFind("archtecture", NoteScope())
            assertTrue(findPureEditTypo.hits.any { it.note.id == projNote.id })
            assertTrue(findPureEditTypo.hits.first { it.note.id == projNote.id }.reasons.any { it.contains("近似拼写") })

            val findScopedOut = env.discovery.searchFind("dsbj", NoteScope(includeTags = setOf(lifeTagId)))
            assertTrue(findScopedOut.hits.isEmpty())

            val smartPinyin = env.search.observeSmart("biji", NoteScope(), limit = 20).first()
            assertTrue(smartPinyin.any { it.id == bookNote.id })
            assertFalse(smartPinyin.any { it.id == archivedBookNote.id })

            val smartTypo = env.search.observeSmart("笔计", NoteScope(), limit = 20).first()
            assertTrue(smartTypo.any { it.id == bookNote.id })

            val smartScopedOut = env.search.observeSmart(
                keyword = "biji",
                scope = NoteScope(includeTags = setOf(lifeTagId)),
                limit = 20,
            ).first()
            assertTrue(smartScopedOut.isEmpty())

            val smartWithArchived = env.search.observeSmart(
                keyword = "biji",
                scope = NoteScope(archived = true),
                limit = 20,
            ).first()
            assertTrue(smartWithArchived.any { it.id == archivedBookNote.id })
        }
    }

    @Test
    fun tagRecommendationCombinesTfIdfAndCoOccurrence() = runBlocking {
        withTempEnv { env ->
            val archTagId = env.org.createTag("架构")
            val prodTagId = env.org.createTag("产品")
            val travelTagId = env.org.createTag("旅行")

            val n1 = commitTextNote(
                env.notes,
                paragraphs = listOf("微服务架构与接口网关设计，重点梳理服务发现、熔断与限流策略。"),
            )
            env.org.tagNote(n1.id, archTagId, attach = true)
            env.org.tagNote(n1.id, prodTagId, attach = true)

            val n2 = commitTextNote(
                env.notes,
                paragraphs = listOf("接口网关限流演练与核心链路降级方案复盘。"),
            )
            env.org.tagNote(n2.id, archTagId, attach = true)
            env.org.tagNote(n2.id, prodTagId, attach = true)

            val n3 = commitTextNote(
                env.notes,
                paragraphs = listOf("周末去杭州西湖旅行路线与酒店预订。"),
            )
            env.org.tagNote(n3.id, travelTagId, attach = true)

            env.discovery.ensureIndexFresh()

            val recs = env.discovery.recommendTagsForDraft(
                draftText = "今天整理了微服务接口网关的限流保护方案",
                currentNoteId = null,
                attachedTagIds = emptySet(),
                limit = 4,
            )
            val recNames = recs.map { it.tag.name }
            assertTrue("Expected 架构 or 产品 in $recNames", "架构" in recNames || "产品" in recNames)
            assertFalse("旅行" in recNames)

            val coOccurRecs = env.discovery.recommendTagsForDraft(
                draftText = "继续补充网关稳定性设计",
                currentNoteId = null,
                attachedTagIds = setOf(archTagId),
                limit = 4,
            )
            assertTrue(coOccurRecs.any { it.tag.id == prodTagId })
            assertFalse(coOccurRecs.any { it.tag.id == archTagId })
        }
    }

    @Test
    fun textRankSimHashClusteringAndSafeMergeWithIdempotencyAndTrashRecovery() = runBlocking {
        withTempEnv { env ->
            val longArticle = (
                "第一段介绍背景：我们在移动端离线知识库中积累了数百条碎片笔记，手工翻找耗时很高。" +
                    "第二段是关键结论：通过本地 TF-IDF、TextRank 句子抽取与 SimHash 查重，可以在完全不联网的前提下完成高效整理。" +
                    "第三段是实施细节：所有归并与回收站操作都采用事务提交并保留来源引用，保证数据零丢失。" +
                    "第四段补充一句日常记录：今天天气不错，午饭后去公园散了步。"
                )
            val summary = LocalTextIntelligence.extractKeySentences(longArticle, maxSentences = 2, maxChars = 180)
            assertTrue(summary.isSummarized)
            assertTrue(summary.sentences.size in 1..2)
            assertTrue(summary.joined.length <= 180)

            val tagAId = env.org.createTag("周报")
            val tagBId = env.org.createTag("性能")

            val dupA1 = commitTextNote(
                env.notes,
                paragraphs = listOf("本周技术周报：完成本地检索与双卡漫步性能优化，补充回归测试验证与设备验收。"),
            )
            val dupA2 = commitTextNote(
                env.notes,
                paragraphs = listOf("本周技术周报：完成本地检索与双卡漫步性能优化，补充回归测试验证。"),
            )
            env.org.tagNote(dupA1.id, tagAId, attach = true)
            env.org.tagNote(dupA2.id, tagBId, attach = true)

            val dupB1 = commitTextNote(
                env.notes,
                paragraphs = listOf("季度复盘总结：聚焦核心流程稳定性提升与离线容灾演练完整记录。"),
            )
            val dupB2 = commitTextNote(
                env.notes,
                paragraphs = listOf("季度复盘总结：聚焦核心流程稳定性提升与离线容灾演练记录。"),
            )

            val groups = env.agent.findScopedDuplicateGroups(NoteScope(), AgentTimeRange.All)
            assertEquals(2, groups.size)
            assertTrue(groups.all { it.similarityPercent >= 65 })

            val groupA = groups.first { group ->
                val ids = setOf(group.primaryNote.id) + group.duplicateNotes.map { it.id }
                dupA1.id in ids && dupA2.id in ids
            }
            val groupB = groups.first { group ->
                val ids = setOf(group.primaryNote.id) + group.duplicateNotes.map { it.id }
                dupB1.id in ids && dupB2.id in ids
            }

            // 1. Preserve originals (moveDuplicatesToTrash = false) + idempotency
            val actionKeepId = "e2e_merge_keep_${groupA.groupId}"
            val resKeep = env.agent.commitMergeDuplicateGroup(
                group = groupA,
                moveDuplicatesToTrash = false,
                actionId = actionKeepId,
            )
            assertTrue(resKeep is AgentActionCommitResult.Committed)
            val mergedKeepId = (resKeep as AgentActionCommitResult.Committed).noteId
            val mergedKeep = env.notes.loadNote(mergedKeepId)
            assertNotNull(mergedKeep)
            assertEquals("merge_cleanup", mergedKeep!!.sourceType)
            val mergedKeepDoc = NoteDocument.decode(mergedKeep.documentJson)
            val refTargetIds = mergedKeepDoc.blocks
                .filter { it.type == "reference" }
                .mapNotNull { it.referenceTarget }
                .toSet()
            assertTrue(dupA1.id in refTargetIds && dupA2.id in refTargetIds)
            assertNull(env.notes.loadNote(dupA1.id)!!.deletedAt)
            assertNull(env.notes.loadNote(dupA2.id)!!.deletedAt)

            val tagsById = env.db.organization().tags().associateBy { it.id }
            val mergedTags = env.db.organization().noteTags()
                .filter { it.noteId == mergedKeepId }
                .mapNotNull { tagsById[it.tagId]?.name }
                .toSet()
            assertTrue("周报" in mergedTags && "性能" in mergedTags && "合并整理" in mergedTags)

            val resKeepRepeat = env.agent.commitMergeDuplicateGroup(
                group = groupA,
                moveDuplicatesToTrash = false,
                actionId = actionKeepId,
            )
            assertTrue(resKeepRepeat is AgentActionCommitResult.AlreadyApplied)

            // 2. Move originals to recoverable Trash (moveDuplicatesToTrash = true)
            val actionTrashId = "e2e_merge_trash_${groupB.groupId}"
            val resTrash = env.agent.commitMergeDuplicateGroup(
                group = groupB,
                moveDuplicatesToTrash = true,
                actionId = actionTrashId,
            )
            assertTrue(resTrash is AgentActionCommitResult.Committed)
            assertNotNull(env.notes.loadNote(dupB1.id)!!.deletedAt)
            assertNotNull(env.notes.loadNote(dupB2.id)!!.deletedAt)

            env.notes.restoreFromTrash(dupB1.id)
            assertNull(env.notes.loadNote(dupB1.id)!!.deletedAt)
        }
    }

    @Test
    fun agentWorkspaceDuplicateCleanupSheetAndPrivacyPillFlow() {
        val primary = buildNoteEntity(
            id = "dup-ui-1",
            title = "设计评审纪要",
            body = "第一段：确定薄荷绿与玻璃层级设计规范。\n第二段：补充移动端字号与留白细节。",
            createdAt = "2026-11-20T08:00:00Z",
        )
        val duplicate = buildNoteEntity(
            id = "dup-ui-2",
            title = "设计评审记录",
            body = "第一段：确定薄荷绿与玻璃层级设计规范。\n第三段：记录无障碍对比度检查结果。",
            createdAt = "2026-11-20T09:00:00Z",
        )
        val group = DuplicateNoteGroup(
            groupId = "grp-ui-1",
            primaryNote = primary,
            duplicateNotes = listOf(duplicate),
            similarityPercent = 84,
            reason = "SimHash + 语义高相似",
            previewText = "确定薄荷绿与玻璃层级设计规范",
        )
        val run = AgentRunEntity(
            id = "run-dup-ui",
            skillId = AgentSkill.DuplicateCleanup.id,
            prompt = AgentSkill.DuplicateCleanup.defaultPrompt,
            scopeJson = "{}",
            status = "completed",
            resultJson = "",
            createdAt = "2026-11-23T08:00:00Z",
            updatedAt = "2026-11-23T08:00:00Z",
        )
        val result = LocalAgentEngine.analyze(
            skill = AgentSkill.DuplicateCleanup,
            prompt = AgentSkill.DuplicateCleanup.defaultPrompt,
            notes = listOf(primary, duplicate),
            chunks = emptyList(),
            tags = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            links = emptyList(),
            scope = NoteScope(),
            timeRange = AgentTimeRange.All,
        ).result

        var uiState by mutableStateOf(AgentUiState())
        var clickedSkill: AgentSkill? = null
        var mergedGroupId: String? = null
        var mergedMoveToTrash: Boolean? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            AgentWorkspace(
                                state = uiState,
                                notesCount = 303,
                                tags = emptyList(),
                                dark = false,
                                onPromptChange = { uiState = uiState.copy(promptDraft = it) },
                                onSelectTimeRange = {},
                                onSelectTagId = {},
                                onRunSkill = { skill ->
                                    clickedSkill = skill
                                    uiState = uiState.copy(
                                        activeRun = run,
                                        activeResult = result,
                                        duplicateGroups = listOf(group),
                                    )
                                },
                                onRunQuestion = {},
                                onStopRun = {},
                                onSelectPastRun = {},
                                onSaveAsNote = {},
                                onExtractTodos = {},
                                onOpenNote = {},
                                onClearMessage = {},
                                onQuickCapture = {},
                                onMergeDuplicateGroup = { g, moveTrash ->
                                    mergedGroupId = g.groupId
                                    mergedMoveToTrash = moveTrash
                                },
                            )
                        }
                    }
                }
            }

            compose.onNodeWithTag("agent-privacy-pill")
                .assertIsDisplayed()
                .assertTextContains("本机离线运行 · 不上传", substring = true)

            compose.onNodeWithTag("agent-skill-duplicate_cleanup")
                .performScrollTo()
                .performClick()
            assertEquals(AgentSkill.DuplicateCleanup, clickedSkill)

            compose.onNodeWithTag("agent-dup-merge-trigger-${group.groupId}")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()

            compose.onNodeWithTag("agent-dup-merge-sheet").assertIsDisplayed()
            compose.onNodeWithTag("agent-dup-merge-trash-switch").performScrollTo().performClick()
            compose.onNodeWithTag("agent-dup-merge-confirm").performScrollTo().performClick()

            assertEquals(group.groupId, mergedGroupId)
            assertEquals(true, mergedMoveToTrash)
        }
    }

    @Test
    fun exploreReviewReasonAndKeySentencesRender() {
        val reviewNote = buildNoteEntity(
            id = "rev-ui-1",
            title = "离线算法验收卡",
            body = "通过 FSRS 曲线按遗忘风险排布每日回顾，并用 TextRank 抽出关键句。",
            createdAt = "2026-11-10T08:00:00Z",
        )
        val exploreState = ExploreUiState(
            review = DailyReviewSnapshot(
                session = ReviewSessionEntity(
                    id = "sess-ui-1",
                    localDate = "2026-11-23",
                    profileKey = "default",
                    configHash = "cfg",
                    round = 1,
                    cardIdsJson = "[\"rev-ui-1\"]",
                    currentIndex = 0,
                    createdAt = "2026-11-23T08:00:00Z",
                    updatedAt = "2026-11-23T08:00:00Z",
                ),
                cards = listOf(
                    ReviewCardModel(
                        note = reviewNote,
                        title = "离线算法验收卡",
                        previewExcerpt = reviewNote.searchText,
                        fullText = reviewNote.searchText,
                        tagNames = listOf("算法"),
                        tagIds = listOf("t-algo"),
                        eventActions = emptySet(),
                        scheduleReason = "批注巩固到期 · 距上次 12 天",
                        keySentences = listOf("通过 FSRS 曲线按遗忘风险排布每日回顾。"),
                    ),
                ),
                currentIndex = 0,
                onThisDay = emptyList(),
                config = ReviewConfig(),
                suppressedCount = 0,
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        val backdrop = rememberGlassBackdrop()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            ExploreScreen(
                                state = exploreState,
                                tags = emptyList(),
                                notesCount = 1,
                                linksCount = 0,
                                openTodosCount = 0,
                                dark = false,
                                backdrop = backdrop,
                                onEnsureLoaded = {},
                                onClearMessage = {},
                                onStartExtraReviewRound = {},
                                onSelectReviewIndex = {},
                                onDeferReviewCard = {},
                                onSuppressReviewCard7Days = {},
                                onExcludeReviewCard = {},
                                onExcludeReviewTag = { _, _ -> },
                                onUpdateReviewConfig = {},
                                onRestoreAllExclusions = {},
                                onAnnotateReviewCard = { _, _ -> },
                                onQueryChange = {},
                                onScopeChange = {},
                                onSearchFind = { _, _ -> },
                                onRefreshIndex = {},
                                onToggleBasket = {},
                                onClearBasket = {},
                                onSaveBasketAsNote = {},
                                onExcludeFindNote = {},
                                onStartWalk = { _, _ -> },
                                onStepWalkForward = {},
                                onStepWalkBack = {},
                                onSaveWalkMemo = {},
                                onFinishWalkPostcard = {},
                                onToggleWalkPrivacy = {},
                                onSaveWalkPostcardAsNote = {},
                                onDismissWalkPostcard = {},
                                onOpenNote = { _, _ -> },
                                onOpenTodos = {},
                                selectedSectionIndex = 0,
                                onSelectSection = {},
                            )
                        }
                    }
                }
            }
            compose.onNodeWithTag("review-fsrs-reason")
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("批注巩固到期", substring = true)
                .assertIsDisplayed()
            compose.onNodeWithTag("review-key-sentences")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun tasksScreenUrgencyBadgeRendersForOverdueUrgentTask() {
        // TasksScreen urgency badge with isolated temp Room DB & unique TaskPreferences
        val taskDb = Room.databaseBuilder(
            appContext,
            NotesDatabase::class.java,
            File(temp.root, "e2e-tasks-${newId()}.db").absolutePath,
        ).build()
        val taskRepo = NotesRepository(taskDb)
        val taskPrefs = TaskPreferences(appContext, prefsName = "e2e_tasks_${newId()}")
        try {
            val overdueDate = LocalDate.now().minusDays(2).toString()
            val urgentCheckbox = NoteBlock.checkbox(
                text = "紧急提交版本验收报告",
                checked = false,
                dueDate = overdueDate,
            )
            val taskNote = runBlocking {
                taskRepo.commit(
                    taskRepo.newDraft("e2e-task-${newId()}").copy(
                        documentJson = NoteDocument(
                            listOf(
                                NoteBlock.paragraph("版本交付待办"),
                                urgentCheckbox,
                            ),
                        ).encode(),
                        editSeq = 1L,
                    ),
                )
            }
            val expectedTag = "task_urgency_badge_${taskNote.id}_${urgentCheckbox.id}"

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme(colorScheme = mintColorScheme(false)) {
                            val backdrop = rememberGlassBackdrop()
                            TasksScreen(
                                repository = taskRepo,
                                preferences = taskPrefs,
                                backdrop = backdrop,
                                tier = GlassTier.Standard,
                                dark = false,
                                onOpenNote = { _, _ -> },
                                capabilityProvider = { ReminderCapability(true, true) },
                            )
                        }
                    }
                }
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodesWithTag(expectedTag, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("task_row_${taskNote.id}_${urgentCheckbox.id}")
                    .assertIsDisplayed()
                compose.onNodeWithTag(expectedTag, useUnmergedTree = true)
                    .assertIsDisplayed()
                compose.onNodeWithText("逾期 2 天", substring = true)
                    .assertIsDisplayed()
            }
        } finally {
            taskDb.close()
        }
    }
}
