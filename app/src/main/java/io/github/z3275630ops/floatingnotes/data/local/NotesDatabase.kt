package io.github.z3275630ops.floatingnotes.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes", indices = [Index("updated_at"), Index("archived_at")])
internal data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String?,
    @ColumnInfo(name = "document_json") val documentJson: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "author_type") val authorType: String = "user",
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String?,
    @ColumnInfo(name = "ai_policy") val aiPolicy: String = "inherit",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "local_revision") val revision: Long,
    @ColumnInfo(name = "server_revision") val serverRevision: Long? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "archived_at") val archivedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    val pinned: Boolean = false,
    @ColumnInfo(name = "last_draft_id") val lastDraftId: String,
    @ColumnInfo(name = "committed_seq") val committedSeq: Long,
)

@Entity(tableName = "drafts", indices = [Index(value = ["slot"], unique = true)])
internal data class DraftEntity(
    @PrimaryKey val id: String,
    val slot: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "base_revision") val baseRevision: Long,
    @ColumnInfo(name = "document_json") val documentJson: String,
    @ColumnInfo(name = "edit_seq") val editSeq: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "source_type") val sourceType: String = "manual",
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
)

@Entity(tableName = "note_versions", primaryKeys = ["note_id", "revision"])
internal data class NoteVersionEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    val revision: Long,
    @ColumnInfo(name = "document_json") val documentJson: String,
    val origin: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(tableName = "task_index", primaryKeys = ["note_id", "block_id"])
internal data class TaskIndexEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "block_id") val blockId: String,
    val checked: Boolean,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
)

@Entity(tableName = "outbox", indices = [Index("note_id", "local_revision", unique = true)])
internal data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "local_revision") val localRevision: Long,
    @ColumnInfo(name = "base_server_revision") val baseServerRevision: Long?,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Dao
internal interface NotesDao {
    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY pinned DESC, updated_at DESC, id")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT * FROM drafts WHERE slot = :slot")
    suspend fun draft(slot: String): DraftEntity?

    @Upsert suspend fun putDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE id = :id AND edit_seq <= :throughSeq")
    suspend fun deleteDraft(id: String, throughSeq: Long)

    @Upsert suspend fun putNote(note: NoteEntity)
    @Insert suspend fun addVersion(version: NoteVersionEntity)
    @Insert suspend fun addOutbox(event: OutboxEntity)
    @Insert suspend fun addTasks(tasks: List<TaskIndexEntity>)

    @Query("DELETE FROM task_index WHERE note_id = :noteId")
    suspend fun deleteTasks(noteId: String)

    @Query("SELECT * FROM task_index WHERE note_id = :noteId")
    suspend fun tasks(noteId: String): List<TaskIndexEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE note_id = :noteId")
    suspend fun eventCount(noteId: String): Int
}

@Database(
    entities = [NoteEntity::class, DraftEntity::class, NoteVersionEntity::class,
        TaskIndexEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class NotesDatabase : RoomDatabase() {
    abstract fun notes(): NotesDao

    companion object {
        fun open(context: Context) =
            Room.databaseBuilder(context.applicationContext, NotesDatabase::class.java, "floating-notes.db").build()
    }
}
