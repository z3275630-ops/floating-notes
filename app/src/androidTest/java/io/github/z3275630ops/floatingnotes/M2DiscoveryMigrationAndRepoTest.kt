package io.github.z3275630ops.floatingnotes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

class M2DiscoveryMigrationAndRepoTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private data class Env(
        val db: NotesDatabase,
        val notesRepo: NotesRepository,
        val orgRepo: OrganizationRepository,
        val discovery: DiscoveryRepository,
    )

    private suspend fun withEnv(block: suspend (Env) -> Unit) {
        val db = Room.databaseBuilder(
            appContext,
            NotesDatabase::class.java,
            File(temp.root, "m2-${newId()}.db").absolutePath,
        ).build()
        val prefs = ExplorePreferences(appContext, prefsName = "test_explore_${newId()}")
        val notesRepo = NotesRepository(db)
        val orgRepo = OrganizationRepository(db)
        val discovery = DiscoveryRepository(db, notesRepo, prefs)
        try {
            block(Env(db, notesRepo, orgRepo, discovery))
        } finally {
            db.close()
        }
    }

    @Test
    fun migration5To6PreservesExistingNotesAndCreatesDiscoveryTables() = runBlocking {
        val dbFile = File(temp.root, "legacy-v5.db")
        val noteId = "note-v5-keeper"
        val docJson = NoteDocument(listOf(NoteBlock.paragraph("版本5迁移测试正文"))).encode()

        val schemaJson = testContext.assets.open(
            "io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/5.json",
        ).bufferedReader().use { it.readText() }
        val root = JSONObject(schemaJson).getJSONObject("database")
        val entities = root.getJSONArray("entities")
        val sqlDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                sqlDb.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) {
                        sqlDb.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                    }
                }
            }
            sqlDb.insertOrThrow(
                "notes",
                null,
                ContentValues().apply {
                    put("id", noteId)
                    put("title", "升级测试")
                    put("document_json", docJson)
                    put("search_text", "版本5迁移测试正文")
                    put("author_type", "user")
                    put("source_type", "manual")
                    putNull("source_url")
                    put("ai_policy", "inherit")
                    put("created_at", "2026-11-10T10:00:00.000Z")
                    put("updated_at", "2026-11-10T10:00:00.000Z")
                    put("local_revision", 1L)
                    putNull("server_revision")
                    put("content_hash", "h5")
                    putNull("archived_at")
                    putNull("deleted_at")
                    put("pinned", 0)
                    put("last_draft_id", "d5")
                    put("committed_seq", 1L)
                    put("media_mask", 0)
                },
            )
            sqlDb.version = 5
        } finally {
            sqlDb.close()
        }

        val migrated = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*NotesDatabase.migrations)
            .build()
        try {
            val loaded = migrated.notes().note(noteId)
            assertNotNull(loaded)
            assertEquals("版本5迁移测试正文", loaded!!.searchText)
            assertTrue(migrated.discovery().allChunks().isEmpty())
            val repo = NotesRepository(migrated)
            val discovery = DiscoveryRepository(
                migrated,
                repo,
                ExplorePreferences(appContext, "mig_prefs_${newId()}"),
            )
            val cov = discovery.ensureIndexFresh()
            assertEquals(1, cov.eligibleCount)
            assertEquals(1, cov.indexedCount)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun findAndEdgeEchoFilterDeletedNotesAndBuildEvidenceBasketNote() = runBlocking {
        withEnv { env ->
            val n1 = env.notesRepo.commit(
                env.notesRepo.newDraft("n1").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("发布前先核对产品验收清单和签名密钥安全"))).encode(),
                    editSeq = 1,
                ),
            )
            val n2 = env.notesRepo.commit(
                env.notesRepo.newDraft("n2").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("产品验收清单确认通过，可以打包"))).encode(),
                    editSeq = 1,
                ),
            )

            val res1 = env.discovery.searchFind("产品验收清单有什么要求", NoteScope())
            assertEquals(setOf(n1.id, n2.id), res1.hits.mapTo(HashSet()) { it.note.id })

            val echoBeforeDelete = env.discovery.findLiveEdgeEcho("正在编写产品验收清单", currentNoteId = "other")
            assertNotNull(echoBeforeDelete)

            // Move hit to trash and confirm immediate disappearance + verifyEdgeEchoHit block (Q013 / Q097)
            env.notesRepo.moveToTrash(n1.id)
            env.discovery.removeChunksForNoteIds(listOf(n1.id))

            val resAfterTrash = env.discovery.searchFind("产品验收清单", NoteScope())
            assertEquals(listOf(n2.id), resAfterTrash.hits.map { it.note.id })

            if (echoBeforeDelete?.note?.id == n1.id) {
                assertNull(env.discovery.verifyEdgeEchoHit(echoBeforeDelete, currentNoteId = "other"))
            }

            // Evidence basket -> consolidated note with clickable reference block (Q015)
            val hit2 = resAfterTrash.hits.single()
            val savedNote = env.discovery.saveEvidenceBasketAsNote(
                query = "产品验收",
                items = listOf(
                    io.github.z3275630ops.floatingnotes.data.discovery.EvidenceBasketItem(
                        noteId = hit2.note.id,
                        noteRevision = hit2.note.revision,
                        noteTitle = hit2.noteTitle,
                        excerpt = hit2.mergedExcerpt,
                        blockId = hit2.matchedBlockId,
                        reasons = hit2.reasons,
                    ),
                ),
            )
            val doc = NoteDocument.decode(savedNote.documentJson)
            assertTrue(doc.blocks.any { it.type == "reference" && it.referenceTarget == n2.id })
        }
    }

    @Test
    fun dailyReviewDeferExcludeAnnotateRestoreAndWalkBackHistoryWorkEndToEnd() = runBlocking {
        withEnv { env ->
            val a = env.notesRepo.commit(
                env.notesRepo.newDraft("a").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("笔记甲：晨间散步观察光影与天气"))).encode(),
                    editSeq = 1,
                ),
            )
            val b = env.notesRepo.commit(
                env.notesRepo.newDraft("b").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("笔记乙：光影摄影笔记与构图心得"))).encode(),
                    editSeq = 1,
                ),
            )
            val c = env.notesRepo.commit(
                env.notesRepo.newDraft("c").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("笔记丙：周报整理与下周计划"))).encode(),
                    editSeq = 1,
                ),
            )

            val snap1 = env.discovery.loadOrCreateDailyReview()
            val snap2 = env.discovery.loadOrCreateDailyReview()
            assertEquals(snap1.cards.map { it.note.id }, snap2.cards.map { it.note.id })
            assertEquals(3, snap1.cards.size)

            // Defer card and create linked annotation (Q011)
            val anno = env.discovery.annotateReviewCardAsNote(snap1.session.id, a, "补充一个今日视角")
            assertTrue(NoteDocument.decode(anno.documentJson).blocks.any { it.referenceTarget == a.id })

            env.discovery.recordReviewAction(snap1.session.id, b.id, "excluded")
            val afterExclude = env.discovery.loadOrCreateDailyReview()
            assertFalse(afterExclude.cards.any { it.note.id == b.id })

            val restoredCount = env.discovery.restoreAllExclusions()
            assertTrue(restoredCount >= 1)

            // Random walk: start -> stepForward -> branch -> stepBack -> postcard (Q019 / Q020)
            val wStart = env.discovery.startWalk(seedNoteId = a.id)!!
            val wStep1 = env.discovery.stepWalkForward(wStart.session.id, forceCrossDirection = false)!!
            assertTrue(wStep1.canStepBack)
            val wStep2 = env.discovery.stepWalkForward(wStep1.session.id, forceCrossDirection = true)!!
            val wBack = env.discovery.stepWalkBack(wStep2.session.id)!!
            assertEquals(wStep1.currentStep?.id, wBack.currentStep?.id)

            val postcard = env.discovery.buildWalkPostcard(wStart.session.id, hideDetailsForPrivacy = true)
            assertEquals(3, postcard.stepCount)
            assertFalse(postcard.markdownBody.contains("晨间散步"))
            val savedPostcard = env.discovery.savePostcardAsNote(postcard)
            assertTrue(savedPostcard.searchText.contains("漫步明信片"))
        }
    }

    @Test
    fun noteDocumentAdjacentParagraphsMergeAndCheckboxBlocksAreIndexed() = runBlocking {
        withEnv { env ->
            val multiDoc = NoteDocument(
                listOf(
                    NoteBlock.paragraph("第一段很短的开场记录"),
                    NoteBlock.paragraph("第二段同样很短，应与前段合并入同一个检索块"),
                    NoteBlock.checkbox("第三项待办：周五前整理薄荷绿设计复盘", checked = false),
                ),
            )
            val saved = env.notesRepo.commit(
                env.notesRepo.newDraft("merge").copy(
                    documentJson = multiDoc.encode(),
                    editSeq = 1,
                ),
            )
            val chunks = LocalRetrievalEngine.buildChunks(saved)
            assertEquals(1, chunks.size)
            assertTrue(chunks.first().excerpt.contains("第一段"))
            assertTrue(chunks.first().excerpt.contains("待办"))
            assertTrue(chunks.first().blockIdsJson.contains(multiDoc.blocks[0].id))
            assertTrue(chunks.first().blockIdsJson.contains(multiDoc.blocks[2].id))
        }
    }
}
