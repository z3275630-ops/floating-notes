package io.github.z3275630ops.floatingnotes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val FTS_BATCH_SIZE = 500

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "notes_fts")
internal data class NoteFtsEntity(
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "indexed_text") val indexedText: String,
)

/**
 * Pre-tokenizes CJK runs into unigrams + adjacent bigrams and ASCII alphanumeric runs into
 * lowercase tokens so SQLite FTS4 with `unicode61` matches arbitrary Chinese substrings and ASCII
 * prefixes reliably across Android API 26–35.
 */
internal object NoteFtsTokenizer {
    private const val MAX_MATCH_TERMS = 12

    fun buildIndexText(title: String?, searchText: String): String {
        val tokens = LinkedHashSet<String>()
        if (!title.isNullOrEmpty()) {
            appendIndexTokens(title, tokens)
        }
        if (searchText.isNotEmpty()) {
            appendIndexTokens(searchText, tokens)
        }
        return if (tokens.isEmpty()) "" else tokens.joinToString(" ")
    }

    fun buildFtsMatchQuery(rawKeyword: String): String? {
        if (rawKeyword.isBlank()) return null
        val terms = LinkedHashSet<String>()
        var i = 0
        val len = rawKeyword.length
        while (i < len && terms.size < MAX_MATCH_TERMS) {
            val ch = rawKeyword[i]
            when {
                isAsciiAlphaNum(ch) -> {
                    val start = i
                    i++
                    while (i < len && isAsciiAlphaNum(rawKeyword[i])) {
                        i++
                    }
                    if (i - start >= 2) {
                        val token = extractLowerAscii(rawKeyword, start, i).replace("\"", "")
                        if (token.isNotEmpty()) {
                            terms.add("$token*")
                        }
                    }
                }
                isCjk(ch) -> {
                    val start = i
                    i++
                    while (i < len && isCjk(rawKeyword[i])) {
                        i++
                    }
                    if (i - start == 1) {
                        terms.add(rawKeyword.substring(start, start + 1))
                    } else {
                        for (idx in start until i - 1) {
                            terms.add(rawKeyword.substring(idx, idx + 2))
                            if (terms.size >= MAX_MATCH_TERMS) break
                        }
                    }
                }
                else -> i++
            }
        }
        return if (terms.isEmpty()) null else terms.joinToString(" ")
    }

    fun toEntity(note: NoteEntity): NoteFtsEntity = NoteFtsEntity(
        noteId = note.id,
        indexedText = buildIndexText(note.title, note.searchText),
    )

    private fun appendIndexTokens(text: String, out: LinkedHashSet<String>) {
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            when {
                isAsciiAlphaNum(ch) -> {
                    val start = i
                    i++
                    while (i < len && isAsciiAlphaNum(text[i])) {
                        i++
                    }
                    out.add(extractLowerAscii(text, start, i))
                }
                isCjk(ch) -> {
                    val start = i
                    i++
                    while (i < len && isCjk(text[i])) {
                        i++
                    }
                    for (idx in start until i) {
                        out.add(text.substring(idx, idx + 1))
                    }
                    for (idx in start until i - 1) {
                        out.add(text.substring(idx, idx + 2))
                    }
                }
                else -> i++
            }
        }
    }

    private fun extractLowerAscii(text: String, start: Int, end: Int): String {
        var hasUpper = false
        for (idx in start until end) {
            if (text[idx] in 'A'..'Z') {
                hasUpper = true
                break
            }
        }
        if (!hasUpper) return text.substring(start, end)
        return buildString(end - start) {
            for (idx in start until end) {
                val ch = text[idx]
                append(if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch)
            }
        }
    }

    private fun isAsciiAlphaNum(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'A'..'Z' || ch in 'a'..'z'

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }
}

internal val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING FTS4(`note_id` TEXT NOT NULL, `indexed_text` TEXT NOT NULL, tokenize=unicode61)",
        )
        db.execSQL("DELETE FROM `notes_fts`")

        val insertStmt = db.compileStatement(
            "INSERT INTO `notes_fts` (`docid`, `note_id`, `indexed_text`) VALUES (?, ?, ?)",
        )
        try {
            var lastRowId: Long? = null
            while (true) {
                val batch = ArrayList<Triple<Long, String, String>>(FTS_BATCH_SIZE)
                var maxRowId = lastRowId ?: 0L
                val query = if (lastRowId == null) {
                    SimpleSQLiteQuery(
                        "SELECT rowid, id, title, search_text FROM notes WHERE deleted_at IS NULL ORDER BY rowid ASC LIMIT ?",
                        arrayOf<Any>(FTS_BATCH_SIZE),
                    )
                } else {
                    SimpleSQLiteQuery(
                        "SELECT rowid, id, title, search_text FROM notes WHERE deleted_at IS NULL AND rowid > ? ORDER BY rowid ASC LIMIT ?",
                        arrayOf<Any>(lastRowId, FTS_BATCH_SIZE),
                    )
                }
                db.query(query).use { rows ->
                    while (rows.moveToNext()) {
                        val rowId = rows.getLong(0)
                        maxRowId = rowId
                        val noteId = rows.getString(1)
                        val title = if (rows.isNull(2)) null else rows.getString(2)
                        val searchText = if (rows.isNull(3)) "" else rows.getString(3)
                        batch.add(Triple(rowId, noteId, NoteFtsTokenizer.buildIndexText(title, searchText)))
                    }
                }
                if (batch.isEmpty()) break
                for ((docId, noteId, indexedText) in batch) {
                    insertStmt.clearBindings()
                    insertStmt.bindLong(1, docId)
                    insertStmt.bindString(2, noteId)
                    insertStmt.bindString(3, indexedText)
                    insertStmt.executeInsert()
                }
                lastRowId = maxRowId
                if (batch.size < FTS_BATCH_SIZE) break
            }
        } finally {
            insertStmt.close()
        }
    }
}
