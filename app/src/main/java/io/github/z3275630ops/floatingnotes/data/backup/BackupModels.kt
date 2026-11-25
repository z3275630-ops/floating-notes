package io.github.z3275630ops.floatingnotes.data.backup

import java.io.IOException

internal class BackupValidationException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object BackupConstants {
    const val SUPPORTED_BACKUP_SCHEMA_VERSION = 2
    const val SUPPORTED_DB_SCHEMA_VERSION = 8
    const val MAX_ARCHIVE_ENTRIES = 10_000
    const val SHARD_NOTES_THRESHOLD = 200
    const val NOTES_PER_SHARD = 500
    const val MAX_ENTRY_BYTES = 128L * 1024L * 1024L
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    const val MAX_COMPRESSION_RATIO = 200L
    const val RATIO_CHECK_MIN_BYTES = 64L * 1024L
    const val MAX_REVISIONS_PER_NOTE = 50
    val DEFAULT_EXCLUSIONS = listOf(
        "api_keys",
        "auth_tokens",
        "signing_credentials",
        "rebuildable_thumbnails",
        "rebuildable_search_indexes",
        "uninserted_audio_transcripts",
    )
}

internal data class BackupEntityCounts(
    val notes: Int,
    val trashedNotes: Int,
    val versions: Int,
    val drafts: Int,
    val tags: Int,
    val notebooks: Int,
    val noteTags: Int,
    val notebookNotes: Int,
    val noteLinks: Int,
    val savedSearches: Int,
    val reminders: Int,
    val taskOccurrences: Int,
    val attachments: Int,
)

internal data class BackupAttachmentEntry(
    val id: String,
    val archivePath: String,
    val originalName: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
)

internal data class BackupFileChecksum(
    val bytes: Long,
    val sha256: String,
)

internal data class BackupManifest(
    val backupSchemaVersion: Int,
    val dbSchemaVersion: Int,
    val exportedAt: String,
    val appVersion: String,
    val counts: BackupEntityCounts,
    val attachments: List<BackupAttachmentEntry>,
    val files: Map<String, BackupFileChecksum>,
    val exclusions: List<String> = BackupConstants.DEFAULT_EXCLUSIONS,
)

internal data class BackupNoteRecord(
    val id: String,
    val title: String?,
    val documentJson: String,
    val searchText: String,
    val authorType: String,
    val sourceType: String,
    val sourceUrl: String?,
    val aiPolicy: String,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
    val serverRevision: Long?,
    val contentHash: String,
    val archivedAt: String?,
    val deletedAt: String?,
    val pinned: Boolean,
    val lastDraftId: String,
    val committedSeq: Long,
    val mediaMask: Int,
)

internal data class BackupVersionRecord(
    val noteId: String,
    val revision: Long,
    val documentJson: String,
    val origin: String,
    val createdAt: String,
    val title: String? = null,
    val attachmentRefs: String = "[]",
)

internal data class BackupDraftRecord(
    val id: String,
    val slot: String,
    val noteId: String,
    val baseRevision: Long,
    val documentJson: String,
    val editSeq: Long,
    val updatedAt: String,
    val sourceType: String,
    val sourceUrl: String?,
)

internal data class BackupTagRecord(
    val id: String,
    val parentId: String,
    val name: String,
    val nameKey: String,
)

internal data class BackupNotebookRecord(
    val id: String,
    val name: String,
    val nameKey: String,
)

internal data class BackupNoteTagRecord(
    val noteId: String,
    val tagId: String,
)

internal data class BackupNotebookNoteRecord(
    val noteId: String,
    val notebookId: String,
)

internal data class BackupNoteLinkRecord(
    val sourceId: String,
    val blockId: String,
    val targetId: String,
    val targetRevision: Long?,
)

internal data class BackupSavedSearchRecord(
    val id: String,
    val name: String,
    val keyword: String,
    val scopeJson: String,
)

internal data class BackupReminderRecord(
    val id: String,
    val noteId: String,
    val blockId: String,
    val occurrenceId: String?,
    val zoneId: String,
    val localDate: String,
    val localTime: String,
    val triggerInstantMs: Long?,
    val policy: String,
    val status: String,
    val recurrence: String,
    val snoozedUntilMs: Long?,
    val missedSummarized: Boolean,
    val revision: Long,
    val updatedAt: String,
)

internal data class BackupTaskOccurrenceRecord(
    val id: String,
    val noteId: String,
    val blockId: String,
    val reminderId: String?,
    val scheduledDate: String?,
    val titleSnapshot: String,
    val recurrence: String,
    val completedAt: String,
)

internal data class BackupSnapshot(
    val exportedAt: String,
    val appVersion: String,
    val dbSchemaVersion: Int,
    val notes: List<BackupNoteRecord>,
    val versions: List<BackupVersionRecord>,
    val drafts: List<BackupDraftRecord>,
    val tags: List<BackupTagRecord>,
    val notebooks: List<BackupNotebookRecord>,
    val noteTags: List<BackupNoteTagRecord>,
    val notebookNotes: List<BackupNotebookNoteRecord>,
    val noteLinks: List<BackupNoteLinkRecord>,
    val savedSearches: List<BackupSavedSearchRecord>,
    val reminders: List<BackupReminderRecord>,
    val taskOccurrences: List<BackupTaskOccurrenceRecord>,
    val attachments: List<BackupAttachmentEntry>,
)

internal data class ParsedBackupArchive(
    val manifest: BackupManifest,
    val snapshot: BackupSnapshot,
)

internal enum class RestorePhase {
    PREFLIGHT,
    ROLLBACK_SAVED,
    STAGED_ATTACHMENTS,
    APPLYING_DB,
    DB_COMMITTED,
    COMPLETED,
    FAILED,
}

internal data class RestoreJournalRecord(
    val id: String,
    val phase: RestorePhase,
    val mode: RestoreMode,
    val rollbackZipPath: String?,
    val leasedAttachmentIds: List<String>,
    val stagedAttachmentIds: List<String>,
    val updatedAt: String,
    val errorMessage: String? = null,
)

/**
 * Pure-Kotlin strict RFC 8259 JSON parser/serializer so backup codecs and diff previews run
 * identically in JVM unit tests and Android instrumentation without org.json mocking gaps.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 64

    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw BackupValidationException("JSON 根节点必须是对象")
    }

    fun parseArray(text: String): List<Any?> {
        val value = parse(text)
        @Suppress("UNCHECKED_CAST")
        return value as? List<Any?> ?: throw BackupValidationException("JSON 根节点必须是数组")
    }

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.eof()) {
            throw BackupValidationException("JSON 末尾包含非法多余字符")
        }
        return value
    }

    fun encode(value: Any?): String = buildString(128) {
        appendJson(this, value)
    }

    private fun appendJson(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Number -> sb.append(value.toString())
            is String -> appendString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    appendJson(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendJson(sb, item)
                }
                sb.append(']')
            }
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Reader(private val src: String) {
        private var pos = 0

        fun eof(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw BackupValidationException("JSON 嵌套层级过深")
            skipWhitespace()
            if (eof()) throw BackupValidationException("JSON 内容不完整")
            return when (val ch = src[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw BackupValidationException("JSON 遇到意外字符: $ch")
            }
        }

        private fun expectLiteral(literal: String, result: Any?): Any? {
            if (!src.startsWith(literal, pos)) throw BackupValidationException("JSON 字面量非法")
            pos += literal.length
            return result
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (eof() || src[pos] != '"') throw BackupValidationException("JSON 对象键必须是字符串")
                val key = readString()
                skipWhitespace()
                if (eof() || src[pos] != ':') throw BackupValidationException("JSON 缺少冒号")
                pos++
                val value = readValue(depth)
                map[key] = value
                skipWhitespace()
                if (eof()) throw BackupValidationException("JSON 对象未闭合")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw BackupValidationException("JSON 对象语法错误")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                val value = readValue(depth)
                list.add(value)
                skipWhitespace()
                if (eof()) throw BackupValidationException("JSON 数组未闭合")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw BackupValidationException("JSON 数组语法错误")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw BackupValidationException("JSON 转义不完整")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw BackupValidationException("JSON Unicode 转义不完整")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw BackupValidationException("JSON Unicode 转义非法: $hex")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw BackupValidationException("JSON 转义字符非法: \\$esc")
                        }
                    }
                    ch.code < 0x20 -> throw BackupValidationException("JSON 字符串包含未转义控制字符")
                    else -> sb.append(ch)
                }
            }
            throw BackupValidationException("JSON 字符串未闭合")
        }

        private fun readNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (pos >= src.length) throw BackupValidationException("JSON 数字不完整")
            if (src[pos] == '0') {
                pos++
            } else if (src[pos] in '1'..'9') {
                while (pos < src.length && src[pos] in '0'..'9') pos++
            } else {
                throw BackupValidationException("JSON 数字格式非法")
            }
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    throw BackupValidationException("JSON 小数格式非法")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    throw BackupValidationException("JSON 指数格式非法")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (!isFloat) {
                token.toLongOrNull() ?: token.toDoubleOrNull()
                    ?: throw BackupValidationException("JSON 整数溢出: $token")
            } else {
                token.toDoubleOrNull() ?: throw BackupValidationException("JSON 浮点数非法: $token")
            }
        }
    }
}

internal fun Map<String, Any?>.reqString(key: String): String {
    val v = this[key]
    return v as? String ?: throw BackupValidationException("缺少或非法的字符串字段: $key")
}

internal fun Map<String, Any?>.optString(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v
    else -> throw BackupValidationException("字段 $key 应为字符串或 null")
}

internal fun Map<String, Any?>.reqLong(key: String): Long = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Number -> v.toLong()
    else -> throw BackupValidationException("缺少或非法的整数字段: $key")
}

internal fun Map<String, Any?>.optLong(key: String): Long? = when (val v = this[key]) {
    null -> null
    is Long -> v
    is Int -> v.toLong()
    is Number -> v.toLong()
    else -> throw BackupValidationException("字段 $key 应为整数或 null")
}

internal fun Map<String, Any?>.reqInt(key: String): Int = reqLong(key).let {
    if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) {
        throw BackupValidationException("字段 $key 超出 Int 范围")
    }
    it.toInt()
}

internal fun Map<String, Any?>.reqBool(key: String): Boolean {
    val v = this[key]
    return v as? Boolean ?: throw BackupValidationException("缺少或非法的布尔字段: $key")
}

internal fun Map<String, Any?>.reqObject(key: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?> ?: throw BackupValidationException("缺少或非法的对象字段: $key")
}

internal fun Map<String, Any?>.reqArray(key: String): List<Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? List<Any?> ?: throw BackupValidationException("缺少或非法的数组字段: $key")
}
