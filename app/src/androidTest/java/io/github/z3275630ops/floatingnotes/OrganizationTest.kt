package io.github.z3275630ops.floatingnotes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OrganizationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun moveMergeAndDeleteTagsWithoutLosingNotes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        try {
            val repo = OrganizationRepository(db)
            val notes = NotesRepository(db)
            val note = save(notes, "正文", NoteDocument(listOf(NoteBlock.paragraph("保存我"))))
            val parent = repo.createTag("旧上级")
            val child = repo.createTag("子标签", parent)
            val target = repo.createTag("新上级")
            repo.tagNote(note.id, child, true)
            assertTrue(runCatching { repo.moveTag(parent, child) }.exceptionOrNull() is IllegalArgumentException)
            repo.moveTag(child, target)
            repo.renameTag(child, "新名称")
            val tree = repo.tags.first()
            assertEquals("新上级 / 新名称", tagPath(tree, tree.single { it.id == child }))
            assertEquals(setOf(target, child), tagSubtree(tree, target))
            repo.mergeTags(child, target)
            assertEquals(listOf(NoteTagEntity(note.id, target)), repo.tagsByNote.first())
            val notebook = repo.createNotebook("项目")
            repo.fileNote(note.id, notebook.id)
            assertEquals(listOf(NotebookNoteEntity(note.id, notebook.id)), repo.notebookNotes.first())
            repo.deleteNotebook(notebook.id)
            repo.deleteTag(target)
            assertTrue(repo.tagsByNote.first().isEmpty())
            assertTrue(repo.notebookNotes.first().isEmpty())
            assertEquals("保存我", notes.loadNote(note.id)?.searchText)
        } finally { db.close() }
        Unit
    }

    @Test fun referenceTracksStableTargetAndIsRemovedOnDocumentEdit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        try {
            val notes = NotesRepository(db)
            val repo = OrganizationRepository(db)
            val target = save(notes, "target", NoteDocument(listOf(NoteBlock.paragraph("原笔记"))))
            val reference = NoteBlock.reference(target.id, target.revision, "原笔记")
            val source = save(notes, "source", NoteDocument(listOf(reference)))
            val tag = repo.createTag("主题")
            repo.tagNote(target.id, tag, true)
            repo.renameTag(tag, "新主题")
            notes.setArchived(target.id, true)
            assertEquals(NoteLinkEntity(source.id, reference.id, target.id, target.revision), repo.links.first().single())
            notes.commit(notes.newDraft("edit", source).copy(
                documentJson = NoteDocument(listOf(NoteBlock.paragraph("移除引用后的正文"))).encode(), editSeq = 1,
            ))
            assertTrue(repo.links.first().isEmpty())
        } finally { db.close() }
        Unit
    }

    @Test fun migrateVersionOneWithoutLosingNotesOrDrafts() = runBlocking {
        val name = "t09-migration-${UUID.randomUUID()}.db"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val target = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()
        val reference = NoteBlock.reference(target, 1, "旧引用")
        val document = NoteDocument(listOf(reference)).encode()
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val table = entities.getJSONObject(index)
                    old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                    val indices = table.optJSONArray("indices")
                    if (indices != null) for (i in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                    }
                }
                old.execSQL(
                    """INSERT INTO notes (id, document_json, search_text, author_type, source_type, ai_policy,
                        created_at, updated_at, local_revision, content_hash, pinned, last_draft_id, committed_seq)
                        VALUES (?, ?, '旧引用', 'user', 'manual', 'inherit', 'time', 'time', 1, 'hash', 0, 'old', 1)""",
                    arrayOf(source, document),
                )
                old.execSQL(
                    """INSERT INTO drafts (id, slot, note_id, base_revision, document_json, edit_seq, updated_at, source_type)
                        VALUES ('old-draft', 'manual', ?, 1, ?, 2, 'time', 'manual')""",
                    arrayOf(source, document),
                )
                old.version = 1
            }
            val db = Room.databaseBuilder(context, NotesDatabase::class.java, name).build()
            try {
                val notes = NotesRepository(db)
                assertEquals(document, notes.loadNote(source)?.documentJson)
                assertEquals("old-draft", notes.loadDraft("manual")?.id)
                assertEquals(NoteLinkEntity(source, reference.id, target, 1), db.organization().observeLinks().first().single())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    private suspend fun save(repo: NotesRepository, slot: String, doc: NoteDocument) =
        repo.commit(repo.newDraft(slot).copy(documentJson = doc.encode(), editSeq = 1))
}
