package io.github.z3275630ops.floatingnotes

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {
    @Test fun draftSurvivesDatabaseReopenAndRepeatedSaveCreatesOneRevision() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = "notes-test-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, NotesDatabase::class.java, file).build()
        val document = NoteDocument(listOf(NoteBlock.paragraph("第一段"), NoteBlock.checkbox("回顾")))
        val draft = NotesRepository(first).newDraft("manual").copy(
            documentJson = document.encode(), editSeq = 1,
        )
        try {
            NotesRepository(first).checkpoint(draft)
            first.close()
            Room.databaseBuilder(context, NotesDatabase::class.java, file).build().withClose { second ->
                val repository = NotesRepository(second)
                assertEquals(draft, repository.loadDraft("manual"))
                val note = repository.commit(draft)
                assertEquals(note, repository.commit(draft))
                repository.checkpoint(draft)
                assertNull(repository.loadDraft("manual"))
                assertEquals(1L, note.revision)
                assertEquals(1, second.notes().eventCount(note.id))
                assertEquals(document.blocks.last().id, second.notes().tasks(note.id).single().blockId)
            }
        } finally {
            first.close()
            context.deleteDatabase(file)
        }
        Unit
    }

    @Test fun outboxFailureRollsBackNoteAndKeepsDraft() = runBlocking {
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), NotesDatabase::class.java,
        ).build().withClose { db ->
            val repository = NotesRepository(db)
            val draft = repository.newDraft("manual").copy(
                editSeq = 1, documentJson = NoteDocument(listOf(NoteBlock.paragraph("保留原文"))).encode(),
            )
            repository.checkpoint(draft)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
            )
            val failure = runCatching { repository.commit(draft) }.exceptionOrNull()
            assertTrue(failure is SQLiteException)
            assertNull(repository.loadNote(draft.noteId))
            assertEquals(0, db.notes().eventCount(draft.noteId))
            assertEquals(draft, repository.loadDraft("manual"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_outbox")
            assertEquals("保留原文", repository.commit(draft).searchText)
        }
        Unit
    }

    @Test fun delayedCheckpointCannotOverwriteNewerText() = runBlocking {
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), NotesDatabase::class.java,
        ).build().withClose { db ->
            val repository = NotesRepository(db)
            val block = NoteBlock.paragraph("旧文字")
            val draft = repository.newDraft("manual").copy(
                editSeq = 1, documentJson = NoteDocument(listOf(block)).encode(),
            )
            val latest = draft.copy(editSeq = 2,
                documentJson = NoteDocument(listOf(block.copy(text = "新文字"))).encode())
            repository.checkpoint(latest)
            repository.checkpoint(draft)
            assertEquals(latest, repository.loadDraft("manual"))
            assertEquals("新文字", repository.commit(latest).searchText)
            assertEquals("新文字", repository.commit(draft).searchText)
        }
        Unit
    }
}

private inline fun <T> NotesDatabase.withClose(block: (NotesDatabase) -> T): T =
    try { block(this) } finally { close() }
