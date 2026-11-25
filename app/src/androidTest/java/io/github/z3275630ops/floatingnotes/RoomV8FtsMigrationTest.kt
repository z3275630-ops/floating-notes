package io.github.z3275630ops.floatingnotes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
import io.github.z3275630ops.floatingnotes.data.local.searchQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RoomV8FtsMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private val timestamp = "2026-11-14T10:00:00.000Z"

    @Test
    fun migration7To8CreatesFtsTableBackfillsActiveNotesAndSyncsWrites() = runBlocking {
        val dbFile = File(temp.root, "legacy-v7-${newId()}.db")
        createV7DatabaseFromSchemaAsset(dbFile) { raw ->
            raw.beginTransaction()
            try {
                insertV7Note(
                    raw = raw,
                    id = "cjk-1",
                    title = "架构讨论",
                    searchText = "今天讨论沟通异步索引方案",
                )
                insertV7Note(
                    raw = raw,
                    id = "punct-1",
                    title = null,
                    searchText = "讨论沟通、作业😀 Android 100%_!",
                )
                insertV7Note(
                    raw = raw,
                    id = "ascii-2",
                    title = "Android 100xyz",
                    searchText = "普通英文测试",
                )
                insertV7Note(
                    raw = raw,
                    id = "single-cjk",
                    title = null,
                    searchText = "水",
                )
                insertV7Note(
                    raw = raw,
                    id = "deleted-1",
                    title = "已删除沟通",
                    searchText = "已删除笔记不应进入沟通索引",
                    deletedAt = timestamp,
                )
                // Cross the 500-row cursor batch boundary during migration backfill.
                for (i in 1..520) {
                    insertV7Note(
                        raw = raw,
                        id = "bulk-$i",
                        title = null,
                        searchText = "批量迁移历史记录 $i",
                    )
                }
                raw.version = 7
                raw.setTransactionSuccessful()
            } finally {
                raw.endTransaction()
            }
        }

        val migrated = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*NotesDatabase.migrations)
            .build()
        try {
            // 1. Verify virtual FTS4 table schema and unicode61 tokenizer.
            var ftsTableSql = ""
            migrated.openHelper.readableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'notes_fts'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                ftsTableSql = cursor.getString(0)
            }
            assertTrue(ftsTableSql.contains("FTS4", ignoreCase = true))
            assertTrue(ftsTableSql.contains("unicode61", ignoreCase = true))

            // 2. Verify backfill excludes soft-deleted notes and includes >500 batch rows.
            var ftsCount = 0
            migrated.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes_fts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                ftsCount = cursor.getInt(0)
            }
            assertEquals(524, ftsCount)

            val initialRows = allFtsRows(migrated)
            assertEquals(524, initialRows.size)
            assertFalse(initialRows.any { it.second == "deleted-1" })
            assertFalse(ftsRowsMatching(migrated, "沟通").any { it.second == "deleted-1" })

            // 3. Verify historical FTS + LIKE dual filtering and pure-LIKE fallback.
            assertEquals(setOf("cjk-1", "punct-1"), findIds(migrated, "沟通"))
            assertEquals(setOf("punct-1"), findIds(migrated, "%_"))
            assertEquals(setOf("punct-1"), findIds(migrated, "、作业😀"))
            assertEquals(setOf("punct-1"), findIds(migrated, "android 100%_!"))
            assertEquals(setOf("single-cjk"), findIds(migrated, "水"))
            assertEquals(emptySet<String>(), findIds(migrated, "讨论 \"沟通\""))

            // 4. Verify putNote update replaces stale FTS entries cleanly.
            val originalCjk1 = migrated.notes().note("cjk-1")!!
            migrated.notes().putNote(
                originalCjk1.copy(
                    title = "全新标题",
                    searchText = "已经改成量子物理笔记",
                    revision = originalCjk1.revision + 1L,
                ),
            )
            assertEquals(setOf("punct-1"), findIds(migrated, "沟通"))
            assertEquals(setOf("cjk-1"), findIds(migrated, "量子"))
            val staleMatches = ftsRowsMatching(migrated, "沟通").filter { it.second == "cjk-1" }
            val cjk1Rows = allFtsRows(migrated).filter { it.second == "cjk-1" }
            assertTrue(
                "Stale FTS rows matching '沟通' for cjk-1: $staleMatches; all cjk-1 rows: $cjk1Rows",
                staleMatches.isEmpty(),
            )
            assertEquals(1, cjk1Rows.size)
            assertTrue(cjk1Rows.single().third.contains("量子"))

            // 5. Verify soft-delete via putNote removes the note from FTS.
            val punct1 = migrated.notes().note("punct-1")!!
            migrated.notes().putNote(
                punct1.copy(
                    deletedAt = "2026-11-15T00:00:00.000Z",
                    revision = punct1.revision + 1L,
                ),
            )
            assertEquals(emptySet<String>(), findIds(migrated, "沟通"))
            assertEquals(emptySet<String>(), findIds(migrated, "%_"))
            assertFalse(ftsRowsMatching(migrated, "沟通").any { it.second == "punct-1" })
            assertFalse(allFtsRows(migrated).any { it.second == "punct-1" })

            // 6. Verify batch insertNotes indexes active notes, skips soft-deleted notes, and cleans stale FTS on overwrite.
            migrated.notes().insertNotes(
                listOf(
                    syntheticNote("batch-active", "新加入的读书笔记"),
                    syntheticNote("batch-deleted", "新加入但已删除的读书笔记", deletedAt = timestamp),
                    syntheticNote("cjk-1", "批量覆盖后的星云内容"),
                ),
            )
            assertEquals(setOf("batch-active"), findIds(migrated, "读书"))
            assertEquals(setOf("cjk-1"), findIds(migrated, "星云"))
            assertEquals(emptySet<String>(), findIds(migrated, "量子"))
            assertFalse(ftsRowsMatching(migrated, "量子").any { it.second == "cjk-1" })
            val cjk1AfterBatch = allFtsRows(migrated).filter { it.second == "cjk-1" }
            assertEquals(1, cjk1AfterBatch.size)
            assertTrue(cjk1AfterBatch.single().third.contains("星云"))
            assertFalse(allFtsRows(migrated).any { it.second == "batch-deleted" })

            // 7. Verify observeSmart hydrates full NoteEntity via lightweight projection pool.
            val smartResults = SearchRepository(migrated).observeSmart("读书记笔", NoteScope(), limit = 20).first()
            assertEquals(listOf("batch-active"), smartResults.map { it.id })
            assertTrue(smartResults.single().documentJson.contains("新加入的读书笔记"))

            // 8. Verify deleteNoteById and clearAllNotes sync FTS.
            migrated.notes().deleteNoteById("batch-active")
            assertEquals(emptySet<String>(), findIds(migrated, "读书"))
            assertFalse(ftsRowsMatching(migrated, "读书").any { it.second == "batch-active" })
            assertFalse(allFtsRows(migrated).any { it.second == "batch-active" })

            migrated.notes().clearAllNotes()
            migrated.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes_fts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    private fun ftsRowsMatching(db: NotesDatabase, matchExpr: String): List<Triple<Long, String, String>> {
        val rows = ArrayList<Triple<Long, String, String>>()
        db.openHelper.readableDatabase.query(
            "SELECT docid, note_id, indexed_text FROM notes_fts WHERE notes_fts MATCH ?",
            arrayOf(matchExpr),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        return rows
    }

    private fun allFtsRows(db: NotesDatabase): List<Triple<Long, String, String>> {
        val rows = ArrayList<Triple<Long, String, String>>()
        db.openHelper.readableDatabase.query(
            "SELECT docid, note_id, indexed_text FROM notes_fts",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        return rows
    }

    private suspend fun findIds(db: NotesDatabase, keyword: String): Set<String> =
        db.search().search(searchQuery(keyword, NoteScope(), 51)).map { it.id }.toSet()

    private fun syntheticNote(
        id: String,
        text: String,
        title: String? = null,
        deletedAt: String? = null,
    ): NoteEntity = NoteEntity(
        id = id,
        title = title,
        documentJson = NoteDocument(listOf(NoteBlock.paragraph(text))).encode(),
        searchText = text,
        sourceType = "manual",
        sourceUrl = null,
        createdAt = timestamp,
        updatedAt = timestamp,
        revision = 1L,
        contentHash = "synthetic",
        deletedAt = deletedAt,
        lastDraftId = id,
        committedSeq = 1L,
    )

    private fun insertV7Note(
        raw: SQLiteDatabase,
        id: String,
        title: String?,
        searchText: String,
        deletedAt: String? = null,
    ) {
        val docJson = NoteDocument(listOf(NoteBlock.paragraph(searchText))).encode()
        raw.insertOrThrow(
            "notes",
            null,
            ContentValues().apply {
                put("id", id)
                if (title == null) putNull("title") else put("title", title)
                put("document_json", docJson)
                put("search_text", searchText)
                put("author_type", "user")
                put("source_type", "manual")
                putNull("source_url")
                put("ai_policy", "inherit")
                put("created_at", timestamp)
                put("updated_at", timestamp)
                put("local_revision", 1L)
                putNull("server_revision")
                put("content_hash", "h-$id")
                putNull("archived_at")
                if (deletedAt == null) putNull("deleted_at") else put("deleted_at", deletedAt)
                put("pinned", 0)
                put("last_draft_id", "d-$id")
                put("committed_seq", 1L)
                put("media_mask", 0)
            },
        )
    }

    private fun createV7DatabaseFromSchemaAsset(
        dbFile: File,
        populate: (SQLiteDatabase) -> Unit,
    ) {
        val schemaJson = testContext.assets.open(
            "io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/7.json",
        ).bufferedReader().use { it.readText() }
        val root = JSONObject(schemaJson).getJSONObject("database")
        val entities = root.getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            populate(raw)
        }
    }
}
