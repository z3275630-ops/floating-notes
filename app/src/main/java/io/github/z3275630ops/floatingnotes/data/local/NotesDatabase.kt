package io.github.z3275630ops.floatingnotes.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "notes",
    indices = [Index("updated_at"), Index("archived_at"), Index("deleted_at")],
)
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
    @ColumnInfo(name = "media_mask", defaultValue = "0") val mediaMask: Int = 0,
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
    val title: String? = null,
    @ColumnInfo(name = "attachment_refs", defaultValue = "'[]'") val attachmentRefs: String = "[]",
)

@Entity(
    tableName = "task_index",
    primaryKeys = ["note_id", "block_id"],
    indices = [Index("due_date"), Index("checked"), Index("occurrence_id")],
)
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

    @Query("SELECT * FROM drafts WHERE source_type = 'share' ORDER BY updated_at DESC")
    fun observeCaptureDrafts(): Flow<List<DraftEntity>>

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

    @Query("SELECT * FROM notes WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC, updated_at DESC, id")
    fun observeTrashedNotes(): Flow<List<NoteEntity>>

    @Query("SELECT id FROM notes WHERE deleted_at IS NOT NULL")
    fun observeTrashedNoteIds(): Flow<List<String>>

    @Query("SELECT * FROM notes WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC, updated_at DESC, id")
    suspend fun trashedNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT * FROM drafts")
    suspend fun allDrafts(): List<DraftEntity>

    @Query("SELECT * FROM note_versions ORDER BY note_id, revision")
    suspend fun allVersions(): List<NoteVersionEntity>

    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY revision DESC")
    suspend fun versionsForNote(noteId: String): List<NoteVersionEntity>

    @Query("SELECT * FROM note_versions WHERE note_id = :noteId AND revision = :revision")
    suspend fun version(noteId: String, revision: Long): NoteVersionEntity?

    @Query(
        """
        DELETE FROM note_versions
        WHERE note_id = :noteId
          AND revision != :keepRevision
          AND revision NOT IN (
            SELECT revision FROM note_versions
            WHERE note_id = :noteId
            ORDER BY revision DESC
            LIMIT :keepCount
          )
        """,
    )
    suspend fun pruneOldVersions(noteId: String, keepCount: Int, keepRevision: Long)

    @Query("DELETE FROM note_versions WHERE note_id = :noteId")
    suspend fun deleteVersionsForNote(noteId: String)

    @Query("DELETE FROM drafts WHERE note_id = :noteId")
    suspend fun deleteDraftsForNote(noteId: String)

    @Query("DELETE FROM outbox WHERE note_id = :noteId AND base_server_revision IS NULL")
    suspend fun deleteUnsyncedOutboxForNote(noteId: String)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDrafts(drafts: List<DraftEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersions(versions: List<NoteVersionEntity>)

    @Query("DELETE FROM drafts")
    suspend fun clearAllDrafts()

    @Query("DELETE FROM note_versions")
    suspend fun clearAllVersions()

    @Query("DELETE FROM outbox")
    suspend fun clearAllOutbox()

    @Query("DELETE FROM notes")
    suspend fun clearAllNotes()
}

@Database(
    entities = [NoteEntity::class, DraftEntity::class, NoteVersionEntity::class,
        TaskIndexEntity::class, OutboxEntity::class, TagEntity::class, NotebookEntity::class,
        NoteTagEntity::class, NotebookNoteEntity::class, NoteLinkEntity::class, SavedSearch::class,
        ReminderEntity::class, TaskOccurrenceEntity::class],
    version = 5,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = LinkBackfill::class)],
    exportSchema = true,
)
internal abstract class NotesDatabase : RoomDatabase() {
    abstract fun notes(): NotesDao
    abstract fun organization(): OrganizationDao
    abstract fun search(): SearchDao
    abstract fun taskReminders(): TaskReminderDao

    companion object {
        const val SCHEMA_VERSION = 5
        private var shared: NotesDatabase? = null
        internal val migrations: Array<Migration> = arrayOf(SearchMigration, MIGRATION_3_4, MIGRATION_4_5)

        @Synchronized
        fun open(context: Context): NotesDatabase = shared ?: Room.databaseBuilder(
            context.applicationContext, NotesDatabase::class.java, "floating-notes.db",
        ).addMigrations(*migrations).build().also { shared = it }
    }
}


internal val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_deleted_at` ON `notes` (`deleted_at`)")
        db.execSQL("ALTER TABLE `note_versions` ADD COLUMN `title` TEXT")
        db.execSQL("ALTER TABLE `note_versions` ADD COLUMN `attachment_refs` TEXT NOT NULL DEFAULT '[]'")
        db.query("SELECT note_id, revision, document_json FROM note_versions").use { rows ->
            while (rows.moveToNext()) {
                val noteId = rows.getString(0)
                val rev = rows.getLong(1)
                val refs = runCatching {
                    val doc = NoteDocument.decode(rows.getString(2))
                    org.json.JSONArray(doc.blocks.mapNotNull { it.attachmentId }.distinct()).toString()
                }.getOrDefault("[]")
                if (refs != "[]") {
                    db.execSQL(
                        "UPDATE `note_versions` SET `attachment_refs` = ? WHERE `note_id` = ? AND `revision` = ?",
                        arrayOf<Any>(refs, noteId, rev),
                    )
                }
            }
        }
    }
}


internal object SearchMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN media_mask INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE saved_searches (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, keyword TEXT NOT NULL, scope_json TEXT NOT NULL)")
        db.query("SELECT id, document_json FROM notes").use { rows ->
            while (rows.moveToNext()) {
                val mask = runCatching { NoteDocument.decode(rows.getString(1)).mediaMask() }.getOrDefault(0)
                if (mask != 0) db.execSQL("UPDATE notes SET media_mask = ? WHERE id = ?", arrayOf<Any>(mask, rows.getString(0)))
            }
        }
    }
}

internal class LinkBackfill : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.query("SELECT id, document_json FROM notes").use { rows ->
            while (rows.moveToNext()) {
                val links = runCatching { NoteDocument.decode(rows.getString(1)).linksFrom(rows.getString(0)) }
                    .getOrDefault(emptyList())
                for (link in links) db.execSQL(
                    "INSERT INTO note_links (sourceId, blockId, targetId, targetRevision) VALUES (?, ?, ?, ?)",
                    arrayOf(link.sourceId, link.blockId, link.targetId, link.targetRevision),
                )
            }
        }
    }
}
