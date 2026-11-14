package io.github.z3275630ops.floatingnotes.data.local

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class NoteScope(
    val updatedTime: Boolean = false,
    val from: Instant? = null,
    val until: Instant? = null,
    val includeTags: Set<String> = emptySet(),
    val excludeTags: Set<String> = emptySet(),
    val descendants: Boolean = true,
    val matchAll: Boolean = false,
    val untagged: Boolean = false,
    val notebooks: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    val media: Set<String> = emptySet(),
    val archived: Boolean = false,
    val noteIds: Set<String> = emptySet(),
    val aiOnly: Boolean = false,
) {
    init {
        require(!untagged || includeTags.isEmpty())
        require(from == null || until == null || from < until)
    }

    fun encode() = JSONObject().apply {
        put("schema_version", 1)
        put("time_axis", if (updatedTime) "updated_at" else "created_at")
        from?.let { put("from_inclusive", it.toString()) }
        until?.let { put("to_exclusive", it.toString()) }
        put("include_tag_ids", JSONArray(includeTags))
        put("exclude_tag_ids", JSONArray(excludeTags))
        put("include_descendants", descendants)
        put("tag_match_mode", if (matchAll) "all" else "any")
        put("untagged_only", untagged)
        put("notebook_ids", JSONArray(notebooks))
        put("source_types", JSONArray(sources))
        put("media_types", JSONArray(media))
        put("include_archived", archived)
        put("include_deleted", false)
        put("explicit_note_ids", JSONArray(noteIds))
        put("ai_access", if (aiOnly) "allowed_only" else "not_applicable")
    }.toString()

    companion object {
        fun decode(text: String): NoteScope {
            val json = JSONObject(text)
            require(json.getInt("schema_version") == 1 && !json.getBoolean("include_deleted"))
            fun ids(key: String) = json.getJSONArray(key).let { values ->
                (0 until values.length()).map(values::getString).toSet()
            }
            return NoteScope(
                updatedTime = json.getString("time_axis") == "updated_at",
                from = json.optString("from_inclusive").takeIf(String::isNotEmpty)?.let(Instant::parse),
                until = json.optString("to_exclusive").takeIf(String::isNotEmpty)?.let(Instant::parse),
                includeTags = ids("include_tag_ids"), excludeTags = ids("exclude_tag_ids"),
                descendants = json.getBoolean("include_descendants"), matchAll = json.getString("tag_match_mode") == "all",
                untagged = json.getBoolean("untagged_only"), notebooks = ids("notebook_ids"),
                sources = ids("source_types"), media = ids("media_types"), archived = json.getBoolean("include_archived"),
                noteIds = ids("explicit_note_ids"), aiOnly = json.getString("ai_access") == "allowed_only",
            )
        }
    }
}

@Entity(tableName = "saved_searches")
internal data class SavedSearch(
    @PrimaryKey val id: String,
    val name: String,
    val keyword: String,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
)

@Dao
internal interface SearchDao {
    @RawQuery(observedEntities = [NoteEntity::class, TagEntity::class, NoteTagEntity::class, NotebookNoteEntity::class])
    fun observe(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<NoteEntity>

    @Query("SELECT * FROM saved_searches ORDER BY rowid DESC")
    fun saved(): Flow<List<SavedSearch>>

    @Insert suspend fun save(search: SavedSearch)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun delete(id: String)
}

internal class SearchRepository(private val database: NotesDatabase) {
    val saved = database.search().saved()

    fun observe(keyword: String, scope: NoteScope, limit: Int) =
        database.search().observe(searchQuery(keyword, scope, limit))

    suspend fun save(name: String, keyword: String, scope: NoteScope) {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 60) { "请输入 1–60 个字的名称" }
        database.search().save(SavedSearch(newId(), label, keyword, scope.encode()))
    }

    suspend fun delete(id: String) = database.search().delete(id)
}

internal val mediaFlags = mapOf("image" to 1, "audio" to 2, "video" to 4)

internal fun NoteDocument.mediaMask(): Int =
    blocks.fold(0) { mask, block -> if (block.attachmentId != null) mask or (mediaFlags[block.type] ?: 0) else mask }

internal fun searchQuery(keyword: String, scope: NoteScope, limit: Int): SimpleSQLiteQuery {
    require(limit > 0)
    val bindings = mutableListOf<Any>()
    val conditions = mutableListOf("n.deleted_at IS NULL")
    fun bind(value: Any): String {
        bindings.add(value)
        return "?"
    }
    fun members(values: Set<String>) = values.joinToString(prefix = "(", postfix = ")") { bind(it) }
    fun matchesTags(ids: Set<String>): String {
        val roots = members(ids)
        val candidates = if (scope.descendants) """
            WITH RECURSIVE selected(id) AS (
                SELECT id FROM tags WHERE id IN $roots
                UNION SELECT tags.id FROM tags JOIN selected ON tags.parentId = selected.id
            )
            SELECT id FROM selected
        """.trimIndent() else "SELECT id FROM tags WHERE id IN $roots"
        return "EXISTS (SELECT 1 FROM note_tags t WHERE t.noteId = n.id AND t.tagId IN ($candidates))"
    }
    if (!scope.archived) conditions.add("n.archived_at IS NULL")
    if (scope.aiOnly) conditions.add("n.ai_policy IN ('inherit', 'allow')")
    val axis = if (scope.updatedTime) "n.updated_at" else "n.created_at"
    scope.from?.let { conditions.add("julianday($axis) >= julianday(${bind(it.toString())})") }
    scope.until?.let { conditions.add("julianday($axis) < julianday(${bind(it.toString())})") }
    if (scope.noteIds.isNotEmpty()) conditions.add("n.id IN ${members(scope.noteIds)}")
    if (scope.sources.isNotEmpty()) conditions.add("n.source_type IN ${members(scope.sources)}")
    if (scope.notebooks.isNotEmpty()) {
        conditions.add("EXISTS (SELECT 1 FROM notebook_notes b WHERE b.noteId = n.id AND b.notebookId IN ${members(scope.notebooks)})")
    }
    if (scope.media.isNotEmpty()) {
        val mask = scope.media.fold(0) { bits, type -> bits or (mediaFlags[type] ?: 0) }
        conditions.add("(n.media_mask & ${bind(mask)}) != 0")
    }
    if (scope.untagged) conditions.add("NOT EXISTS (SELECT 1 FROM note_tags t WHERE t.noteId = n.id)")
    if (scope.includeTags.isNotEmpty()) {
        if (scope.matchAll) scope.includeTags.forEach { conditions.add(matchesTags(setOf(it))) }
        else conditions.add(matchesTags(scope.includeTags))
    }
    if (scope.excludeTags.isNotEmpty()) conditions.add("NOT ${matchesTags(scope.excludeTags)}")
    val phrase = keyword.trim()
    if (phrase.isNotEmpty()) {
        val pattern = "%" + phrase.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        conditions.add("(n.search_text LIKE ${bind(pattern)} ESCAPE '!' OR n.title LIKE ${bind(pattern)} ESCAPE '!')")
    }
    return SimpleSQLiteQuery(
        "SELECT n.* FROM notes n WHERE ${conditions.joinToString(" AND ")} " +
            "ORDER BY n.pinned DESC, n.updated_at DESC, n.id LIMIT ${bind(limit)}",
        bindings.toTypedArray(),
    )
}
