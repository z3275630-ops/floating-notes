package io.github.z3275630ops.floatingnotes

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.backup.BackupUiState
import io.github.z3275630ops.floatingnotes.data.backup.DiffLineKind
import io.github.z3275630ops.floatingnotes.data.backup.HistoryUiState
import io.github.z3275630ops.floatingnotes.data.backup.MissingAttachmentInfo
import io.github.z3275630ops.floatingnotes.data.backup.NoteRevisionSummary
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestorePreflight
import io.github.z3275630ops.floatingnotes.data.backup.TrashPurgeImpact
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiffLine
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiffPreview
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.feature.home.BackupRestoreSheet
import io.github.z3275630ops.floatingnotes.feature.home.NoteHistorySheet
import io.github.z3275630ops.floatingnotes.feature.home.TrashView
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class T14UiFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun noteHistorySheetShowsTimelineDiffMissingAttachmentsAndRestoresNewRevision() {
        var selectedRev by mutableStateOf(2L)
        var restoredPair: Pair<String, Long>? = null
        val rev1Diff = VersionDiffPreview(
            noteId = "note-h1",
            targetRevision = 1L,
            currentRevision = 2L,
            origin = "edit",
            createdAt = "2026-11-15T08:00:00Z",
            titleChanged = true,
            currentTitle = "新版标题",
            targetTitle = "初版标题",
            lines = listOf(
                VersionDiffLine(DiffLineKind.REMOVED, "被删掉的旧段"),
                VersionDiffLine(DiffLineKind.ADDED, "恢复后的内容"),
            ),
            totalAttachmentCount = 1,
            missingAttachments = listOf(MissingAttachmentInfo("att-missing-1", "missing-voice.m4a")),
        )
        val rev2Diff = VersionDiffPreview(
            noteId = "note-h1",
            targetRevision = 2L,
            currentRevision = 2L,
            origin = "edit",
            createdAt = "2026-11-15T09:00:00Z",
            titleChanged = false,
            currentTitle = "新版标题",
            targetTitle = "新版标题",
            lines = listOf(VersionDiffLine(DiffLineKind.UNCHANGED, "当前正文")),
            totalAttachmentCount = 0,
            missingAttachments = emptyList(),
        )
        val revisions = listOf(
            NoteRevisionSummary(
                noteId = "note-h1",
                revision = 2L,
                origin = "edit",
                title = "新版标题",
                snippet = "当前正文",
                attachmentCount = 0,
                createdAt = "2026-11-15T09:00:00Z",
                isCurrent = true,
            ),
            NoteRevisionSummary(
                noteId = "note-h1",
                revision = 1L,
                origin = "edit",
                title = "初版标题",
                snippet = "恢复后的内容",
                attachmentCount = 1,
                createdAt = "2026-11-15T08:00:00Z",
                isCurrent = false,
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                        val backdrop = rememberGlassBackdrop()
                        NoteHistorySheet(
                            state = HistoryUiState(
                                noteId = "note-h1",
                                currentRevision = 2L,
                                revisions = revisions,
                                selectedRevision = selectedRev,
                                diffPreview = if (selectedRev == 1L) rev1Diff else rev2Diff,
                            ),
                            backdrop = backdrop,
                            dark = false,
                            onDismiss = {},
                            onSelectRevision = { selectedRev = it },
                            onRestoreRevision = { noteId, rev -> restoredPair = noteId to rev },
                        )
                    }
                }
            }

            compose.onNodeWithTag("note-history-sheet").assertIsDisplayed()
            compose.onNodeWithTag("history-revision-2").assertIsDisplayed()
            compose.onNodeWithTag("history-header-restore").assertDoesNotExist()

            compose.onNodeWithTag("history-revision-1").performClick()
            compose.waitUntil(5_000) { selectedRev == 1L }

            compose.onNodeWithTag("history-diff-preview").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("missing-voice.m4a", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("history-restore-button").performScrollTo().performClick()
            assertEquals("note-h1" to 1L, restoredPair)
        }
    }

    @Test
    fun trashViewRendersEmptyAndPopulatedStatesRestoreAndPurgeImpactBackupShortcut() {
        val sampleNote = NoteEntity(
            id = "trash-n1",
            title = "待清理的草稿",
            documentJson = NoteDocument(listOf(NoteBlock.paragraph("待清理的草稿"), NoteBlock.paragraph("细节内容"))).encode(),
            searchText = "待清理的草稿\n细节内容",
            sourceType = "manual",
            sourceUrl = null,
            createdAt = "2026-11-15T08:00:00Z",
            updatedAt = "2026-11-15T09:00:00Z",
            revision = 3L,
            contentHash = "hash-1",
            deletedAt = "2026-11-15T10:00:00Z",
            lastDraftId = "draft-1",
            committedSeq = 3L,
        )
        var trashedList by mutableStateOf<List<NoteEntity>>(emptyList())
        var impact by mutableStateOf<TrashPurgeImpact?>(null)
        var restoredId: String? = null
        var emptiedCount = 0
        var backupShortcutClicks = 0

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                        val backdrop = rememberGlassBackdrop()
                        TrashView(
                            trashedNotes = trashedList,
                            purgeImpact = impact,
                            dark = false,
                            backdrop = backdrop,
                            onOpenDrawer = {},
                            onRestore = { restoredId = it.id },
                            onPreparePurgeNote = { impact = TrashPurgeImpact(1, 3, 2) },
                            onPrepareEmptyTrash = { impact = TrashPurgeImpact(1, 3, 2) },
                            onConfirmPurgeNote = {},
                            onConfirmEmptyTrash = { emptiedCount++ },
                            onClearPurgeImpact = { impact = null },
                            onOpenBackupExport = { backupShortcutClicks++ },
                        )
                    }
                }
            }

            compose.onNodeWithTag("trash-screen").assertIsDisplayed()
            compose.onNodeWithTag("trash-retention-banner").assertIsDisplayed()
            compose.onNodeWithText("没有已删除的笔记").assertIsDisplayed()

            trashedList = listOf(sampleNote)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("trashed-note").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("trash-restore-trash-n1").performClick()
            assertEquals("trash-n1", restoredId)

            compose.onNodeWithTag("trash-purge-trash-n1").performClick()
            compose.onNodeWithTag("trash-purge-confirm-dialog").assertIsDisplayed()
            compose.onNodeWithText("将永久清除 1 条笔记、3 个历史版本，并清理 2 个不再被引用的附件", substring = true)
                .assertIsDisplayed()
            compose.onNodeWithTag("trash-purge-export-shortcut").performClick()
            assertEquals(1, backupShortcutClicks)

            compose.onNodeWithTag("trash-empty-button").performClick()
            compose.onNodeWithTag("trash-purge-confirm-dialog").assertIsDisplayed()
            compose.onNode(hasAnyAncestor(isDialog()) and hasText("彻底删除")).performClick()
            assertEquals(1, emptiedCount)
        }
    }

    @Test
    fun backupRestoreSheetShowsWarningsNonDestructiveErrorAndPreflightOptions() {
        var uiState by mutableStateOf(
            BackupUiState(
                errorMessage = "备份文件校验失败或已损坏，无法读取 manifest.json",
            ),
        )
        var todoStartup by mutableStateOf(false)
        var confirmed = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                        val backdrop = rememberGlassBackdrop()
                        BackupRestoreSheet(
                            state = uiState,
                            todoStartupEnabled = todoStartup,
                            onToggleTodoStartup = { todoStartup = it },
                            backdrop = backdrop,
                            dark = false,
                            onDismiss = {},
                            onExportFullBackup = {},
                            onExportMarkdownBundle = {},
                            onPreflightBackup = {},
                            onSetRestoreMode = { mode -> uiState = uiState.copy(preflightMode = mode) },
                            onSetAutoBackupBeforeRestore = { enabled ->
                                uiState = uiState.copy(autoBackupBeforeRestore = enabled)
                            },
                            onConfirmRestore = { confirmed = true },
                            onDismissPreflight = { uiState = uiState.copy(preflight = null) },
                            onClearOutcome = { uiState = uiState.copy(errorMessage = null) },
                        )
                    }
                }
            }

            compose.onNodeWithTag("backup-restore-sheet").assertIsDisplayed()
            compose.onNodeWithTag("backup-error-banner").assertIsDisplayed()
            compose.onNodeWithText("现有笔记库与本地附件未作任何修改。").assertIsDisplayed()

            compose.onNodeWithTag("backup-export-zip").assertIsDisplayed()
            compose.onNodeWithTag("backup-import-zip").assertIsDisplayed()

            compose.onNodeWithTag("backup-startup-todo-toggle").assertIsOff()
            compose.onNodeWithTag("backup-startup-todo-toggle").performClick()
            assertTrue(todoStartup)

            compose.onNodeWithTag("backup-export-markdown").performClick()
            compose.onNodeWithText("导出 Markdown 说明").assertIsDisplayed()
            compose.onNodeWithText("Markdown 导出会降级块级 UUID", substring = true).assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()

            uiState = uiState.copy(
                errorMessage = null,
                preflight = RestorePreflight(
                    sourceUri = "content://backup.zip",
                    exportedAt = "2026-11-15T10:00:00Z",
                    schemaVersion = 1,
                    dbSchemaVersion = 5,
                    totalNotes = 8,
                    trashedNotes = 1,
                    versionCount = 14,
                    attachmentCount = 2,
                    tagCount = 3,
                    notebookCount = 2,
                    savedSearchCount = 1,
                    reminderCount = 2,
                    conflictNoteCount = 2,
                    verifiedSha256 = true,
                ),
            )

            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("backup-preflight-sheet").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("已通过").assertIsDisplayed()
            compose.onNodeWithText("检测到 2 条同 ID 冲突笔记").assertIsDisplayed()
            compose.onNodeWithText("完整恢复（覆盖当前库）").performScrollTo().performClick()
            assertEquals(RestoreMode.REPLACE_ALL, uiState.preflightMode)

            compose.onNodeWithTag("backup-preflight-autobackup").performScrollTo().assertIsOn()
            compose.onNodeWithTag("backup-preflight-confirm").performClick()
            assertTrue(confirmed)
        }
    }

    @Test
    fun endToEndVersionHistoryAndTrashRestoreViaMainActivity() {
        val suffix = UUID.randomUUID().toString().take(8)
        val initialTitle = "T14历史初版-$suffix"
        val updatedTitle = "T14历史二版-$suffix"
        val updatedCard = hasTestTag("saved-note") and hasText(updatedTitle, substring = true)

        ActivityScenario.launch(MainActivity::class.java).use {
            await(hasTestTag("new-note"))
            compose.onNodeWithTag("new-note").performClick()
            await(hasTestTag("capture-editor"))
            compose.onNodeWithTag("capture-editor").performTextReplacement(initialTitle)
            compose.onNodeWithText("保存").performClick()

            val initialCard = hasTestTag("saved-note") and hasText(initialTitle, substring = true)
            await(initialCard)
            compose.onNode(initialCard).performClick()
            await(hasTestTag("note-start-editing"))
            compose.onNodeWithTag("note-start-editing").performClick()
            await(hasTestTag("capture-editor"))
            compose.onNodeWithTag("capture-editor").performTextReplacement(updatedTitle)
            await(hasText("已保存到本机"))
            compose.onNodeWithTag("note-done").performClick()
            await(hasTestTag("note-reading"))
            compose.onNodeWithTag("note-page-back").performClick()
            await(updatedCard)

            val cardMenu = hasTestTag("note-actions") and hasAnyAncestor(updatedCard)
            compose.onNode(cardMenu).performClick()
            compose.onNodeWithText("版本历史").performClick()
            await(hasTestTag("note-history-sheet"))
            compose.onNodeWithTag("history-revision-1").assertIsDisplayed().performClick()
            await(hasTestTag("history-diff-preview"))
            compose.onNodeWithText("关闭").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("note-history-sheet").fetchSemanticsNodes().isEmpty()
            }

            compose.onNode(cardMenu).performClick()
            compose.onNodeWithText("移到废纸篓").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(updatedCard).fetchSemanticsNodes().isEmpty()
            }

            compose.onNodeWithTag("open-notes-drawer").performClick()
            await(hasTestTag("drawer-trash"))
            compose.onNodeWithTag("drawer-trash").performClick()
            await(hasTestTag("trash-screen"))

            val trashedRow = hasTestTag("trashed-note") and hasText(updatedTitle, substring = true)
            await(trashedRow)
            compose.onNode(hasText("恢复") and hasAnyAncestor(trashedRow)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(trashedRow).fetchSemanticsNodes().isEmpty()
            }

            compose.onNodeWithTag("open-notes-drawer").performClick()
            compose.onNodeWithText("全部笔记").performClick()
            await(updatedCard)

            compose.onNode(cardMenu).performClick()
            compose.onNodeWithText("移到废纸篓").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(updatedCard).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("open-notes-drawer").performClick()
            await(hasTestTag("drawer-trash"))
            compose.onNodeWithTag("drawer-trash").performClick()
            await(hasTestTag("trash-screen"))
            await(trashedRow)
            compose.onNode(hasText("彻底删除") and hasAnyAncestor(trashedRow)).performClick()
            compose.onNodeWithTag("trash-purge-confirm-dialog").assertIsDisplayed()
            compose.onNode(hasAnyAncestor(isDialog()) and hasText("彻底删除")).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(trashedRow).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
}
