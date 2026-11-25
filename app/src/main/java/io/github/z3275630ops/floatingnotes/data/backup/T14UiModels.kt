package io.github.z3275630ops.floatingnotes.data.backup

enum class RestoreMode {
    MERGE_KEEP_CONFLICTS,
    REPLACE_ALL,
}

enum class DiffLineKind {
    UNCHANGED,
    ADDED,
    REMOVED,
}

data class NoteRevisionSummary(
    val noteId: String,
    val revision: Long,
    val origin: String,
    val title: String?,
    val snippet: String,
    val attachmentCount: Int,
    val createdAt: String,
    val isCurrent: Boolean,
)

data class VersionDiffLine(
    val kind: DiffLineKind,
    val text: String,
)

data class MissingAttachmentInfo(
    val attachmentId: String,
    val label: String,
)

data class VersionDiffPreview(
    val noteId: String,
    val targetRevision: Long,
    val currentRevision: Long,
    val origin: String,
    val createdAt: String,
    val titleChanged: Boolean,
    val currentTitle: String?,
    val targetTitle: String?,
    val lines: List<VersionDiffLine>,
    val totalAttachmentCount: Int,
    val missingAttachments: List<MissingAttachmentInfo>,
)

data class HistoryUiState(
    val noteId: String? = null,
    val currentRevision: Long = 0L,
    val revisions: List<NoteRevisionSummary> = emptyList(),
    val selectedRevision: Long? = null,
    val diffPreview: VersionDiffPreview? = null,
    val loading: Boolean = false,
    val restoring: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

data class TrashPurgeImpact(
    val noteCount: Int,
    val versionCount: Int,
    val orphanedAttachmentCount: Int,
)

data class RestorePreflight(
    val sourceUri: String,
    val exportedAt: String,
    val schemaVersion: Int,
    val dbSchemaVersion: Int,
    val totalNotes: Int,
    val trashedNotes: Int,
    val versionCount: Int,
    val attachmentCount: Int,
    val tagCount: Int,
    val notebookCount: Int,
    val savedSearchCount: Int,
    val reminderCount: Int,
    val conflictNoteCount: Int,
    val verifiedSha256: Boolean,
)

sealed interface RestoreOutcome {
    data class Success(
        val mode: RestoreMode,
        val importedNotes: Int,
        val skippedOrRenamedNotes: Int,
        val rollbackPath: String?,
        val pausedRemindersNote: String?,
    ) : RestoreOutcome

    data class Failure(
        val message: String,
        val existingLibraryIntact: Boolean = true,
    ) : RestoreOutcome
}

data class ExportOutcome(
    val ok: Boolean,
    val message: String,
    val itemCount: Int = 0,
    val warnings: List<String> = emptyList(),
)

data class BackupUiState(
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val preflight: RestorePreflight? = null,
    val preflightMode: RestoreMode = RestoreMode.MERGE_KEEP_CONFLICTS,
    val autoBackupBeforeRestore: Boolean = true,
    val lastExportOutcome: ExportOutcome? = null,
    val lastRestoreOutcome: RestoreOutcome? = null,
    val errorMessage: String? = null,
    val markdownExportWarnings: List<String> = DEFAULT_MARKDOWN_WARNINGS,
) {
    companion object {
        val DEFAULT_MARKDOWN_WARNINGS = listOf(
            "Markdown 导出会降级块级 UUID 与引用 UTF-16 字符选区，仅保留可读文本与链接。",
            "版本历史（note_versions）、已保存搜索（saved_searches）及本地定时提醒计划仅保留在完整 ZIP 备份中。",
        )
    }
}

internal data class ExternalImportUiState(
    val busy: Boolean = false,
    val preview: ExternalImportPreview? = null,
    val skipDuplicates: Boolean = true,
    val outcome: ExternalImportCommitResult? = null,
    val error: String? = null,
)
