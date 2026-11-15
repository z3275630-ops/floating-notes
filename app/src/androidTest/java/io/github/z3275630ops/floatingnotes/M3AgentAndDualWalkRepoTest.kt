package io.github.z3275630ops.floatingnotes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionCommitResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentRepository
import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M3AgentAndDualWalkRepoTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private data class Env(
        val db: NotesDatabase,
        val notes: NotesRepository,
        val org: OrganizationRepository,
        val discovery: DiscoveryRepository,
        val agent: AgentRepository,
    )

    private suspend fun withEnv(block: suspend (Env) -> Unit) {
        val db = Room.databaseBuilder(
            appContext,
            NotesDatabase::class.java,
            File(temp.root, "m3-${newId()}.db").absolutePath,
        ).build()
        val prefs = ExplorePreferences(appContext, prefsName = "m3_prefs_${newId()}")
        val notes = NotesRepository(db)
        val org = OrganizationRepository(db)
        val discovery = DiscoveryRepository(db, notes, prefs)
        val agent = AgentRepository(db, notes, org, discovery)
        try {
            block(Env(db, notes, org, discovery, agent))
        } finally {
            db.close()
        }
    }

    @Test
    fun migration6To7CreatesAgentTablesAndPreservesNotes() = runBlocking {
        val dbFile = File(temp.root, "legacy-v6.db")
        val keeperId = "note-v6-keeper"
        val docJson = NoteDocument(listOf(NoteBlock.paragraph("版本6升级前已有笔记"))).encode()

        val schemaJson = testContext.assets.open(
            "io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/6.json",
        ).bufferedReader().use { it.readText() }
        val root = JSONObject(schemaJson).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            val entities = root.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val ent = entities.getJSONObject(i)
                val table = ent.getString("tableName")
                val createSql = ent.getString("createSql").replace("\${TABLE_NAME}", table)
                raw.execSQL(createSql)
                val indices = ent.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) {
                        val idxSql = indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                        raw.execSQL(idxSql)
                    }
                }
            }
            raw.insert(
                "notes",
                null,
                ContentValues().apply {
                    put("id", keeperId)
                    putNull("title")
                    put("document_json", docJson)
                    put("search_text", "版本6升级前已有笔记")
                    put("author_type", "user")
                    put("source_type", "manual")
                    putNull("source_url")
                    put("ai_policy", "inherit")
                    put("created_at", "2026-11-14T10:00:00.000Z")
                    put("updated_at", "2026-11-14T10:00:00.000Z")
                    put("local_revision", 1L)
                    putNull("server_revision")
                    put("content_hash", "h-v6")
                    putNull("archived_at")
                    putNull("deleted_at")
                    put("pinned", 0)
                    put("last_draft_id", "draft-v6")
                    put("committed_seq", 1L)
                    put("media_mask", 0)
                },
            )
            raw.version = 6
        } finally {
            raw.close()
        }

        val migrated = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*NotesDatabase.migrations)
            .build()
        try {
            val loaded = migrated.notes().note(keeperId)
            assertNotNull(loaded)
            assertEquals("版本6升级前已有笔记", loaded?.searchText)
            assertTrue(migrated.agent().recentRuns().isEmpty())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun agentActionIdempotencyAndConflictCheckWorkDurably() = runBlocking {
        withEnv { env ->
            val n1 = env.notes.commit(
                env.notes.newDraft("d1").copy(
                    documentJson = NoteDocument(
                        listOf(
                            NoteBlock.paragraph("整理周末阅读清单与实验记录"),
                            NoteBlock.checkbox(text = "核对双卡笔记引用格式", checked = false),
                        ),
                    ).encode(),
                    editSeq = 1L,
                ),
            )
            val n2 = env.notes.commit(
                env.notes.newDraft("d2").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("周末想做异步索引优化"))).encode(),
                    editSeq = 1L,
                ),
            )

            val (run, result) = env.agent.runAnalysis(
                skill = AgentSkill.TodoTriage,
                prompt = AgentSkill.TodoTriage.defaultPrompt,
                scope = NoteScope(),
                timeRange = AgentTimeRange.All,
            )
            assertEquals("completed", run.status)
            assertEquals(1, env.db.agent().recentRuns().size)

            val firstSave = env.agent.commitSaveInsightAsNote(run, result, actionId = "act-save-1")
            assertTrue(firstSave is AgentActionCommitResult.Committed)

            // Same actionId twice -> AlreadyApplied, no duplicate note created
            val dupSave = env.agent.commitSaveInsightAsNote(run, result, actionId = "act-save-1")
            assertTrue(dupSave is AgentActionCommitResult.AlreadyApplied)

            // Modify referenced note n1 -> new actionId refuses stale commit
            val editedDraft = env.notes.newDraft("d1-edit", n1).copy(
                documentJson = NoteDocument(listOf(NoteBlock.paragraph("已大幅修改内容"))).encode(),
                editSeq = n1.committedSeq + 1,
            )
            env.notes.commit(editedDraft)
            val staleCommit = env.agent.commitSaveInsightAsNote(run, result, actionId = "act-save-2")
            assertTrue(staleCommit is AgentActionCommitResult.ConflictDetected)

            // Verify dual collision draw & idempotent save too
            val draw = env.discovery.drawDualCollisionPair()
            assertTrue(draw is DiscoveryRepository.DualPairDrawResult.Ready)
            val pair = (draw as DiscoveryRepository.DualPairDrawResult.Ready).pair
            val bridge = env.discovery.analyzeDualCollision(pair)
            val dualSave1 = env.discovery.saveDualCollisionAsNote(
                pair = pair,
                bridge = bridge,
                userReflection = "两条启发可以串联思考",
                farFetched = false,
                actionId = "dual-act-1",
            )
            assertTrue(dualSave1 is DiscoveryRepository.DualCollisionSaveResult.Saved)
            val dualSave2 = env.discovery.saveDualCollisionAsNote(
                pair = pair,
                bridge = bridge,
                userReflection = "两条启发可以串联思考",
                farFetched = false,
                actionId = "dual-act-1",
            )
            assertTrue(dualSave2 is DiscoveryRepository.DualCollisionSaveResult.AlreadySaved)
            assertNotNull(n2.id)
        }
    }
}
