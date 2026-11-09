package io.github.z3275630ops.floatingnotes.data.local

import androidx.room.*
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tags", indices = [Index(value = ["parentId", "nameKey"], unique = true)])
internal data class TagEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val name: String,
    val nameKey: String,
)

@Entity(tableName = "notebooks", indices = [Index(value = ["nameKey"], unique = true)])
internal data class NotebookEntity(@PrimaryKey val id: String, val name: String, val nameKey: String)

@Entity(
    tableName = "note_tags", primaryKeys = ["noteId", "tagId"], indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class NoteTagEntity(val noteId: String, val tagId: String)

@Entity(
    tableName = "notebook_notes", indices = [Index("notebookId")],
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = NotebookEntity::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class NotebookNoteEntity(@PrimaryKey val noteId: String, val notebookId: String)

@Entity(tableName = "note_links", primaryKeys = ["sourceId", "blockId"], indices = [Index("targetId")])
internal data class NoteLinkEntity(val sourceId: String, val blockId: String, val targetId: String, val targetRevision: Long?)

@Dao
internal interface OrganizationDao {
    @Query("SELECT * FROM tags ORDER BY nameKey, id") fun observeTags(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tags") suspend fun tags(): List<TagEntity>
    @Query("SELECT * FROM notebooks ORDER BY nameKey, id") fun observeNotebooks(): Flow<List<NotebookEntity>>
    @Query("SELECT * FROM note_tags") fun observeTagsByNote(): Flow<List<NoteTagEntity>>
    @Query("SELECT * FROM notebook_notes") fun observeNotebookNotes(): Flow<List<NotebookNoteEntity>>
    @Query("SELECT * FROM note_links") fun observeLinks(): Flow<List<NoteLinkEntity>>
    @Insert suspend fun insertTag(tag: TagEntity)
    @Update suspend fun updateTag(tag: TagEntity)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTag(id: String)
    @Insert suspend fun insertNotebook(notebook: NotebookEntity)
    @Query("UPDATE notebooks SET name = :name, nameKey = :key WHERE id = :id")
    suspend fun renameNotebook(id: String, name: String, key: String)
    @Query("DELETE FROM notebooks WHERE id = :id") suspend fun deleteNotebook(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun attachTag(row: NoteTagEntity)
    @Query("DELETE FROM note_tags WHERE noteId = :note AND tagId = :tag")
    suspend fun detachTag(note: String, tag: String)
    @Query("INSERT OR IGNORE INTO note_tags (noteId, tagId) SELECT noteId, :target FROM note_tags WHERE tagId = :source")
    suspend fun copyTagNotes(source: String, target: String)
    @Upsert suspend fun setNotebook(row: NotebookNoteEntity)
    @Query("DELETE FROM notebook_notes WHERE noteId = :id") suspend fun clearNotebook(id: String)
    @Query("DELETE FROM note_links WHERE sourceId = :id") suspend fun clearLinks(id: String)
    @Insert suspend fun insertLinks(links: List<NoteLinkEntity>)
}

internal class OrganizationRepository(private val db: NotesDatabase) {
    private val dao = db.organization()
    val tags = dao.observeTags()
    val notebooks = dao.observeNotebooks()
    val tagsByNote = dao.observeTagsByNote()
    val notebookNotes = dao.observeNotebookNotes()
    val links = dao.observeLinks()

    suspend fun createTag(name: String, parent: String = "") = db.withTransaction {
        val rows = dao.tags()
        require(parent.isEmpty() || rows.any { it.id == parent }) { "上级标签不存在" }
        val tag = TagEntity(newId(), parent, displayName(name), nameKey(name))
        checkSibling(rows, tag)
        dao.insertTag(tag)
        tag.id
    }

    suspend fun renameTag(id: String, name: String) = db.withTransaction {
        val rows = dao.tags()
        val tag = rows.single { it.id == id }.copy(name = displayName(name), nameKey = nameKey(name))
        checkSibling(rows, tag)
        dao.updateTag(tag)
    }

    suspend fun moveTag(id: String, parent: String) = db.withTransaction {
        val rows = dao.tags()
        require(parent.isEmpty() || rows.any { it.id == parent }) { "上级标签不存在" }
        require(parent !in tagSubtree(rows, id)) { "不能移入自身或下级标签" }
        val tag = rows.single { it.id == id }.copy(parentId = parent)
        checkSibling(rows, tag)
        dao.updateTag(tag)
    }

    suspend fun mergeTags(source: String, target: String) = db.withTransaction {
        val rows = dao.tags()
        require(rows.any { it.id == source } && rows.any { it.id == target }) { "标签不存在" }
        require(target !in tagSubtree(rows, source)) { "不能合并到自身或下级标签" }
        val children = rows.filter { it.parentId == source }
        children.forEach { checkSibling(rows.filterNot { row -> row.id == source }, it.copy(parentId = target)) }
        dao.copyTagNotes(source, target)
        dao.deleteTag(source)
        children.forEach { dao.updateTag(it.copy(parentId = target)) }
    }

    suspend fun deleteTag(id: String) = db.withTransaction {
        val rows = dao.tags()
        val tag = rows.single { it.id == id }
        val children = rows.filter { it.parentId == id }
        children.forEach { checkSibling(rows.filterNot { it.id == id }, it.copy(parentId = tag.parentId)) }
        dao.deleteTag(id)
        children.forEach { dao.updateTag(it.copy(parentId = tag.parentId)) }
    }

    suspend fun createNotebook(name: String): NotebookEntity {
        val notebook = NotebookEntity(newId(), displayName(name), nameKey(name))
        dao.insertNotebook(notebook)
        return notebook
    }
    suspend fun renameNotebook(id: String, name: String) = dao.renameNotebook(id, displayName(name), nameKey(name))
    suspend fun deleteNotebook(id: String) = dao.deleteNotebook(id)
    suspend fun tagNote(note: String, tag: String, attach: Boolean) {
        if (attach) dao.attachTag(NoteTagEntity(note, tag)) else dao.detachTag(note, tag)
    }

    suspend fun fileNote(note: String, notebook: String?) {
        if (notebook == null) dao.clearNotebook(note) else dao.setNotebook(NotebookNoteEntity(note, notebook))
    }

    private fun checkSibling(rows: List<TagEntity>, candidate: TagEntity) {
        require(rows.none { it.id != candidate.id && it.parentId == candidate.parentId && it.nameKey == candidate.nameKey }) {
            "同一级已有同名标签，请改名或合并"
        }
    }

    private fun displayName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 60 && '/' !in it && it.none(Char::isISOControl)) {
            "名称须为 1–60 个字符，且不能包含斜杠或控制字符"
        }
    }

    private fun nameKey(name: String) = Normalizer.normalize(displayName(name), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
}

internal fun tagSubtree(rows: List<TagEntity>, root: String): Set<String> {
    val children = rows.groupBy(TagEntity::parentId)
    val seen = mutableSetOf(root)
    val pending = ArrayDeque<String>().apply { add(root) }
    while (pending.isNotEmpty()) {
        children[pending.removeFirst()].orEmpty().forEach { if (seen.add(it.id)) pending.add(it.id) }
    }
    return seen
}

internal fun tagPath(rows: List<TagEntity>, tag: TagEntity): String {
    val byId = rows.associateBy(TagEntity::id)
    val visited = mutableSetOf<String>()
    val path = mutableListOf<String>()
    var current: TagEntity? = tag
    while (current != null && visited.add(current.id)) {
        path.add(current.name)
        current = byId[current.parentId]
    }
    return path.asReversed().joinToString(" / ")
}

internal fun NoteDocument.linksFrom(source: String): List<NoteLinkEntity> = blocks.mapNotNull { block ->
    block.referenceTarget?.let { NoteLinkEntity(source, block.id, it, block.referenceRevision) }
}
