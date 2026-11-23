package io.github.z3275630ops.floatingnotes

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionCommitResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentRepository
import io.github.z3275630ops.floatingnotes.data.agent.DuplicateGroupSnapshot
import io.github.z3275630ops.floatingnotes.data.backup.BackupManager
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestoreOutcome
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import io.github.z3275630ops.floatingnotes.feature.capture.TranscriptionStore
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
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

class P1PolishEndToEndTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Env(
        val db: NotesDatabase,
        val notes: NotesRepository,
        val org: OrganizationRepository,
        val attachments: AttachmentStore,
        val backup: BackupManager,
        val prefs: ExplorePreferences,
        val discovery: DiscoveryRepository,
        val agent: AgentRepository,
    )

    private suspend fun withTempEnv(block: suspend (Env) -> Unit) {
        val rootDir = File(temp.root, "env-${newId()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(appContext, NotesDatabase::class.java).build()
        val attachmentsDir = File(rootDir, "attachments").apply { mkdirs() }
        val store = AttachmentStore(appContext, attachmentsDir) { 10L * 1024L * 1024L * 1024L }
        val notes = NotesRepository(db)
        val org = OrganizationRepository(db)
        val backup = BackupManager(appContext, db, store)
        val prefs = ExplorePreferences(appContext, prefsName = "e2e_p1_${newId()}")
        val discovery = DiscoveryRepository(db, notes, prefs)
        val agent = AgentRepository(db, notes, org, discovery)
        try {
            block(Env(db, notes, org, store, backup, prefs, discovery, agent))
        } finally {
            prefs.clearAllExclusions()
            db.close()
        }
    }

    @Test
    fun purgeExpiredTrashDeletesOver30DaysWhileKeepingExcludedAndRecentNotes() = runBlocking {
        withTempEnv { env ->
            val expiredDoc = NoteDocument(listOf(NoteBlock.paragraph("过期超过三十天的废弃草稿"))).encode()
            val excludedDoc = NoteDocument(listOf(NoteBlock.paragraph("过期但当前仍打开预览"))).encode()
            val recentDoc = NoteDocument(listOf(NoteBlock.paragraph("昨天刚移入回收站"))).encode()

            env.db.notes().insertNotes(
                listOf(
                    NoteEntity(
                        id = "n-expired",
                        title = "已过期笔记",
                        documentJson = expiredDoc,
                        searchText = "过期超过三十天的废弃草稿",
                        authorType = "user",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-09-01T08:00:00Z",
                        updatedAt = "2026-10-01T08:00:00Z",
                        revision = 1L,
                        contentHash = "h-exp",
                        deletedAt = "2026-10-15T08:00:00Z",
                        lastDraftId = "d-exp",
                        committedSeq = 1L,
                    ),
                    NoteEntity(
                        id = "n-excluded",
                        title = "排除清理笔记",
                        documentJson = excludedDoc,
                        searchText = "过期但当前仍打开预览",
                        authorType = "user",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-09-01T08:00:00Z",
                        updatedAt = "2026-10-01T08:00:00Z",
                        revision = 1L,
                        contentHash = "h-excl",
                        deletedAt = "2026-10-15T08:00:00Z",
                        lastDraftId = "d-excl",
                        committedSeq = 1L,
                    ),
                    NoteEntity(
                        id = "n-recent",
                        title = "最近删除笔记",
                        documentJson = recentDoc,
                        searchText = "昨天刚移入回收站",
                        authorType = "user",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-11-20T08:00:00Z",
                        updatedAt = "2026-11-21T08:00:00Z",
                        revision = 1L,
                        contentHash = "h-rec",
                        deletedAt = "2026-11-22T08:00:00Z",
                        lastDraftId = "d-rec",
                        committedSeq = 1L,
                    ),
                ),
            )

            val res = env.notes.purgeExpiredTrash(
                now = Instant.parse("2026-11-23T08:00:00Z"),
                retentionDays = NotesRepository.TRASH_RETENTION_DAYS,
                excludeNoteIds = setOf("n-excluded"),
            )
            assertEquals(listOf("n-expired"), res.deletedNoteIds)

            val remainingIds = env.db.notes().allNotes().map { it.id }.toSet()
            assertEquals(setOf("n-excluded", "n-recent"), remainingIds)
        }
    }

    @Test
    fun transcriptionStoreDeletesCorruptJsonAndParsesValidTranscript() = runBlocking {
        val voiceDir = File(temp.root, "voice-case").apply { mkdirs() }
        val audioFile = File(voiceDir, "clip.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val transcriptFile = File(voiceDir, "transcript.json")
        val store = TranscriptionStore(appContext, audioFile)

        transcriptFile.writeText("{not-valid-json", StandardCharsets.UTF_8)
        assertNull(store.read())
        assertFalse("Corrupt transcript.json should be cleaned up", transcriptFile.exists())

        transcriptFile.writeText("""{"raw":"只有原始字段"}""", StandardCharsets.UTF_8)
        assertNull(store.read())
        assertFalse("Incomplete transcript.json should be cleaned up", transcriptFile.exists())

        transcriptFile.writeText("""{"raw":"明天十点开会","formatted":"明天十点开会。"}""", StandardCharsets.UTF_8)
        val valid = store.read()
        assertNotNull(valid)
        assertEquals("明天十点开会", valid!!.raw)
        assertEquals("明天十点开会。", valid.formatted)
    }

    @Test
    fun edgeEchoAndSearchVerifyFreshContentHashAndExcludePrivacyPolicy() = runBlocking {
        withTempEnv { env ->
            val v1Doc = NoteDocument(listOf(NoteBlock.paragraph("旧版本段落包含火星计划关键词"))).encode()
            val committed = env.notes.commit(
                env.notes.newDraft("echo-target").copy(
                    documentJson = v1Doc,
                    editSeq = 1L,
                ),
            )
            env.discovery.syncChunksForNote(committed)
            val blockId = NoteDocument.decode(committed.documentJson).blocks.first().id

            // Update note directly with different content/hash at same revision to verify contentHash + excerpt checks.
            val modifiedDoc = NoteDocument(listOf(NoteBlock.paragraph("已改写为普通午餐菜单记录"))).encode()
            env.db.notes().insertNotes(
                listOf(
                    committed.copy(
                        documentJson = modifiedDoc,
                        searchText = "已改写为普通午餐菜单记录",
                        contentHash = "changed-hash-same-rev",
                        updatedAt = "2026-11-23T09:00:00Z",
                    ),
                ),
            )

            val allNotes = env.db.notes().allNotes()
            val allChunks = env.db.discovery().allChunks()
            val searchResult = LocalRetrievalEngine.searchEvidence(
                query = "火星计划",
                scope = NoteScope(aiOnly = true),
                allNotes = allNotes,
                allChunks = allChunks,
                tags = emptyList(),
                noteTagIdsByNote = emptyMap(),
                noteNotebookIdsByNote = emptyMap(),
            )
            assertTrue("Stale chunk must not match edited note content when contentHash changed", searchResult.hits.isEmpty())

            val fakeHit = EvidenceHit(
                note = committed,
                noteTitle = "旧版本",
                mergedExcerpt = "火星计划关键词",
                matchedBlockId = blockId,
                chunkIds = listOf("c1"),
                reasons = emptyList(),
                tagNames = emptyList(),
                score = 1.0,
            )
            assertNull("verifyEdgeEchoHit must drop hit whose excerpt no longer exists in updated block", env.discovery.verifyEdgeEchoHit(fakeHit, null))

            env.db.notes().insertNotes(listOf(committed.copy(aiPolicy = "exclude")))
            val privacyHit = fakeHit.copy(mergedExcerpt = "火星计划", matchedBlockId = null)
            assertNull("verifyEdgeEchoHit must reject aiPolicy=exclude notes", env.discovery.verifyEdgeEchoHit(privacyHit, null))
        }
    }

    @Test
    fun observeTaskItemsTracksTasksAndReviewFlowIsIdempotent() = runBlocking {
        withTempEnv { env ->
            val note1 = env.notes.commit(
                env.notes.newDraft("r1").copy(
                    documentJson = NoteDocument(
                        listOf(
                            NoteBlock.paragraph("回看卡片一内容深度思考记录"),
                            NoteBlock.checkbox("核对回看测试待办"),
                        ),
                    ).encode(),
                    editSeq = 1L,
                ),
            )
            val note2 = env.notes.commit(
                env.notes.newDraft("r2").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("回看卡片二复盘项目方法论"))).encode(),
                    editSeq = 1L,
                ),
            )

            val tasks = env.notes.observeTaskItems().first()
            assertEquals(1, tasks.size)
            assertEquals("核对回看测试待办", tasks.single().text)

            val reviewNow = Instant.now().plusSeconds(5)
            val snapshot = env.discovery.loadOrCreateDailyReview(
                now = reviewNow,
                zoneId = ZoneId.of("UTC"),
            )
            assertEquals(2, snapshot.cards.size)

            env.discovery.setReviewIndex(snapshot.session.id, 1, now = reviewNow.plusSeconds(1))
            env.discovery.markReviewCardSeen(snapshot.session.id, note1.id, now = reviewNow.plusSeconds(2))
            env.discovery.markReviewCardSeen(snapshot.session.id, note1.id, now = reviewNow.plusSeconds(3))
            val seenEvents = env.db.discovery().allReviewEvents().filter {
                it.sessionId == snapshot.session.id && it.noteId == note1.id && it.action == "seen"
            }
            assertEquals("Duplicate markReviewCardSeen must stay idempotent", 1, seenEvents.size)

            env.discovery.recordReviewAction(
                sessionId = snapshot.session.id,
                noteId = note2.id,
                action = "annotated",
                now = reviewNow.plusSeconds(4),
                zoneId = ZoneId.of("UTC"),
            )
            val reloadedSession = env.db.discovery().reviewSession(snapshot.session.id)
            assertEquals("recordReviewAction must preserve persisted currentIndex", 1, reloadedSession?.currentIndex)
        }
    }

    @Test
    fun dualCollisionRefreshTagCacheAgentRunIdAndBackupRoundtripWorkEndToEnd() = runBlocking {
        withTempEnv { env ->
            val left = env.notes.commit(
                env.notes.newDraft("d-left").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("双卡碰撞系统架构设计方法论思考笔记，内容足够长用于合并整理。"))).encode(),
                    editSeq = 1L,
                ),
            )
            val right = env.notes.commit(
                env.notes.newDraft("d-right").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("双卡碰撞知识管理工作流记录，讨论日常灵感整理和标签分类规则。"))).encode(),
                    editSeq = 1L,
                ),
            )
            val hidden = env.notes.commit(
                env.notes.newDraft("d-hidden").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("不应被双卡抽中的已隐藏笔记"))).encode(),
                    editSeq = 1L,
                ),
            )
            env.prefs.addExcludedFindNote(hidden.id)

            val draw = env.discovery.drawDualCollisionPair()
            assertTrue(draw is DiscoveryRepository.DualPairDrawResult.Ready)
            val pair = (draw as DiscoveryRepository.DualPairDrawResult.Ready).pair
            assertEquals(setOf(left.id, right.id), setOf(pair.leftNote.id, pair.rightNote.id))

            // Edit right note and confirm analyzeDualCollisionRefreshed returns refreshed card revision.
            val rightDraft = DraftEntity(
                id = newId(),
                slot = "note:${right.id}",
                noteId = right.id,
                baseRevision = right.revision,
                documentJson = NoteDocument(listOf(NoteBlock.paragraph("新版双卡碰撞知识管理工作流，已补充系统架构连接点。"))).encode(),
                editSeq = 2L,
                updatedAt = "2026-11-23T09:05:00.000Z",
                sourceType = right.sourceType,
                sourceUrl = right.sourceUrl,
            )
            val updatedRight = env.notes.commit(rightDraft)
            val analyzed = env.discovery.analyzeDualCollisionRefreshed(pair)
            assertTrue(analyzed is DiscoveryRepository.DualBridgeAnalyzeResult.Ready)
            val alignedPair = (analyzed as DiscoveryRepository.DualBridgeAnalyzeResult.Ready).pair
            val alignedRightRev = if (alignedPair.rightNote.id == updatedRight.id) alignedPair.rightRevision else alignedPair.leftRevision
            assertEquals(updatedRight.revision, alignedRightRev)

            // Tag cache invalidation after attaching tag & committing new note (via k-NN without direct tag-name mention).
            val tag = TagEntity(newId(), "", "航天总装", tagNameKey("航天总装"))
            env.db.organization().insertTag(tag)
            val taggedNote = env.notes.commit(
                env.notes.newDraft("d-tagged").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("推进剂 姿控 发动机 热防护"))).encode(),
                    editSeq = 1L,
                ),
            )
            env.db.organization().attachTag(NoteTagEntity(taggedNote.id, tag.id))
            val firstRecs = env.discovery.recommendTagsForDraft("推进剂 姿控 热防护", null, emptySet())
            assertTrue(firstRecs.any { it.tag.id == tag.id })

            val extraTag = TagEntity(newId(), "", "深空测控", tagNameKey("深空测控"))
            env.db.organization().insertTag(extraTag)
            val taggedNote2 = env.notes.commit(
                env.notes.newDraft("d-tagged-2").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("射电阵列 轨道测控 遥测信号 推进剂"))).encode(),
                    editSeq = 1L,
                ),
            )
            env.db.organization().attachTag(NoteTagEntity(taggedNote2.id, extraTag.id))
            val secondRecs = env.discovery.recommendTagsForDraft("射电阵列 轨道测控 遥测信号", null, emptySet())
            assertTrue(secondRecs.any { it.tag.id == extraTag.id })

            // Move right note to trash -> analyzeDualCollisionRefreshed must report Unavailable.
            env.notes.moveToTrash(right.id)
            val unavailable = env.discovery.analyzeDualCollisionRefreshed(pair)
            assertEquals(DiscoveryRepository.DualBridgeAnalyzeResult.Unavailable, unavailable)

            // Hydrate duplicate groups & verify runId linkage on merge commit.
            val dupA = env.notes.commit(
                env.notes.newDraft("m1").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("待合并的相似笔记本条目一"))).encode(),
                    editSeq = 1L,
                ),
            )
            val dupB = env.notes.commit(
                env.notes.newDraft("m2").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("待合并的相似笔记本条目二"))).encode(),
                    editSeq = 1L,
                ),
            )
            val hydrated = env.agent.hydrateDuplicateGroups(
                listOf(
                    DuplicateGroupSnapshot(
                        groupId = "grp-1",
                        primaryNoteId = dupA.id,
                        primaryRevision = dupA.revision,
                        duplicateNoteIds = listOf(dupB.id),
                        duplicateRevisions = mapOf(dupB.id to dupB.revision),
                        similarityPercent = 88,
                        reason = "高度相似",
                        previewText = "待合并条目",
                    ),
                ),
            )
            assertEquals(1, hydrated.size)
            val commitOutcome = env.agent.commitMergeDuplicateGroup(
                group = hydrated.single(),
                moveDuplicatesToTrash = false,
                runId = "run-p1-verify",
            )
            assertTrue(commitOutcome is AgentActionCommitResult.Committed)
            val linkedActions = env.db.agent().actionsForRun("run-p1-verify")
            assertEquals(1, linkedActions.size)
            assertEquals("run-p1-verify", linkedActions.single().runId)

            // Backup export & restore roundtrip without in-memory staged archive cache.
            val backupZip = File(temp.root, "p1-roundtrip.zip")
            env.backup.exportFullBackupToFile(backupZip)
            val preflight = env.backup.preflightBackupFile(backupZip)
            assertTrue(preflight.verifiedSha256)
            val restore = env.backup.executeRestore(mode = RestoreMode.REPLACE_ALL, autoBackupBeforeRestore = false)
            assertTrue(restore.outcome is RestoreOutcome.Success)
        }
    }
}
