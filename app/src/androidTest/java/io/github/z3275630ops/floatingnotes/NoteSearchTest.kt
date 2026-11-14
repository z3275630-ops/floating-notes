package io.github.z3275630ops.floatingnotes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.time.Instant
import java.util.UUID

class NoteSearchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val timestamp = "2026-11-14T04:00:00.000Z"

    @Test fun searchFilterAndRestoreSavedConditionsInLibrary() {
        val token = "合成查找" + UUID.randomUUID().toString().take(8)
        val savedName = "筛选 $token"
        val db = NotesDatabase.open(context)
        runBlocking { db.notes().putNote(note(token, token)) }
        compose.onNodeWithTag("tab-Library").performClick()
        compose.onNodeWithTag("library-search").performTextInput(token)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("saved-note").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(token).assertExists()
        compose.onNodeWithText("筛选").performClick()
        compose.onNodeWithText("仅无标签").performClick()
        compose.onNodeWithText("应用筛选").performClick()
        compose.onNodeWithText("保存条件").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(savedName)
        compose.onNodeWithText("保存搜索").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("已保存搜索").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("重置条件").performClick()
        compose.onNodeWithText("已保存搜索").performClick()
        compose.onNodeWithText(savedName).performClick()
        compose.onNodeWithText("筛选已启用").assertExists()
    }

    @Test fun literalChinesePunctuationAndAscii() = runBlocking {
        memory { db ->
            db.notes().putNote(note("one", "讨论沟通、作业😀 Android 100%_!"))
            db.notes().putNote(note("two", "讨论沟通，作业 Android 100xyz"))
            db.notes().putNote(note("three", "未知", title = "Android 100%_!"))
            assertEquals(setOf("one", "two"), find(db, "沟通"))
            assertEquals(setOf("one"), find(db, "、作业😀"))
            assertEquals(setOf("one", "three"), find(db, "android 100%_!"))
            assertEquals(setOf("one", "three"), find(db, "%_"))
            assertEquals(emptySet<String>(), find(db, "不存在"))
        }
    }

    @Test fun scopeIntersectsEachDimensionAndExclusionWins() = runBlocking {
        memory { db ->
            val org = OrganizationRepository(db)
            val parent = org.createTag("父级")
            val child = org.createTag("子级", parent)
            val second = org.createTag("附加")
            val excluded = org.createTag("排除")
            val book = org.createNotebook("合成笔记本").id
            for (id in listOf("hit", "blocked", "partial", "archived", "deleted", "untagged")) {
                db.notes().putNote(note(id, "两字样本").copy(
                    sourceType = "share", mediaMask = 2,
                    archivedAt = timestamp.takeIf { id == "archived" }, deletedAt = timestamp.takeIf { id == "deleted" },
                ))
                org.fileNote(id, book)
                if (id != "untagged") {
                    org.tagNote(id, child, true)
                    if (id != "partial") org.tagNote(id, second, true)
                    if (id == "blocked") org.tagNote(id, excluded, true)
                }
            }
            val scope = NoteScope(
                includeTags = setOf(parent, second), excludeTags = setOf(excluded),
                matchAll = true, notebooks = setOf(book), sources = setOf("share"), media = setOf("audio"),
                from = Instant.parse(timestamp), until = Instant.parse(timestamp).plusSeconds(1),
            )
            assertEquals(scope, NoteScope.decode(scope.encode()))
            assertEquals(setOf("hit"), find(db, "样本", scope))
            assertEquals(setOf("hit", "archived"), find(db, "样本", scope.copy(archived = true)))
            assertEquals(setOf("hit", "partial"), find(db, "样本", scope.copy(matchAll = false)))
            assertEquals(emptySet<String>(), find(db, "样本", scope.copy(descendants = false)))
            assertEquals(emptySet<String>(), find(db, "样本", scope.copy(noteIds = setOf("blocked"))))
            assertEquals(setOf("untagged"), find(db, "样本", NoteScope(untagged = true)))
            val saved = SearchRepository(db)
            saved.save("我的检索", "样本", scope)
            val item = saved.saved.first().single()
            assertEquals(setOf("hit"), find(db, item.keyword, NoteScope.decode(item.scopeJson)))
            saved.delete(item.id)
            assertTrue(saved.saved.first().isEmpty())
        }
    }

    @Test fun upgradeV2BackfillsMediaAndPreservesDocument() = runBlocking {
        val name = "search-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val fixture = "io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/2.json"
        val database = InstrumentationRegistry.getInstrumentation().context.assets.open(fixture)
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val document = NoteDocument(listOf(NoteBlock.attachment("audio", UUID.randomUUID().toString()))).encode()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                val tables = database.getJSONArray("entities")
                for (i in 0 until tables.length()) {
                    val table = tables.getJSONObject(i)
                    val nameInSchema = table.getString("tableName")
                    old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", nameInSchema))
                    val indices = table.getJSONArray("indices")
                    for (j in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", nameInSchema))
                    }
                }
                old.execSQL("""INSERT INTO notes (id, document_json, search_text, author_type, source_type, ai_policy,
                    created_at, updated_at, local_revision, content_hash, pinned, last_draft_id, committed_seq)
                    VALUES ('old', ?, '', 'user', 'manual', 'inherit', ?, ?, 1, 'hash', 0, 'draft', 1)""",
                    arrayOf(document, timestamp, timestamp))
                old.version = 2
            }
            Room.databaseBuilder(context, NotesDatabase::class.java, name).addMigrations(SearchMigration).build().let { db ->
                try {
                val result = db.search().search(searchQuery("", NoteScope(media = setOf("audio")), 50)).single()
                assertEquals(document, result.documentJson)
                assertEquals(2, result.mediaMask)
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun measureTenThousandNotesWithSmallResultPages() = runBlocking {
        memory { db ->
            db.withTransaction {
                for (i in 1..10_000) {
                    db.notes().putNote(note("n-$i", "普通笔记 计划和沟通 Android 每天记录 $i".repeat(20)))
                }
            }
            for ((phrase, count) in listOf("沟通" to 51, "没有匹配的短语" to 0)) {
                val query = searchQuery(phrase, NoteScope(), 51)
                db.search().search(query)
                val times = List(20) {
                    val begin = SystemClock.elapsedRealtimeNanos()
                    assertEquals(count, db.search().search(query).size)
                    (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
                }.sorted()
                Log.i("FloatingSearchBench", "fixture=10000 count=$count p95_ms=${times[18]}")
            }
        }
    }

    private fun note(id: String, text: String, title: String? = null) = NoteEntity(
        id, title, NoteDocument(listOf(NoteBlock.paragraph(text))).encode(), text,
        sourceType = "manual", sourceUrl = null, createdAt = timestamp, updatedAt = timestamp,
        revision = 1, contentHash = "synthetic", lastDraftId = id, committedSeq = 1,
    )

    private suspend fun find(db: NotesDatabase, query: String, scope: NoteScope = NoteScope()) =
        db.search().search(searchQuery(query, scope, 51)).map { it.id }.toSet()

    private suspend fun memory(test: suspend (NotesDatabase) -> Unit) = withContext(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        try { test(db) } finally { db.close() }
    }
}
