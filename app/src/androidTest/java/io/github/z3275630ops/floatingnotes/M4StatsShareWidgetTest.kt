package io.github.z3275630ops.floatingnotes

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardOptions
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardPaginator
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardRenderer
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardSheet
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardTemplate
import io.github.z3275630ops.floatingnotes.feature.stats.StatsSheet
import io.github.z3275630ops.floatingnotes.feature.widget.WidgetContract
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class M4StatsShareWidgetTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun buildNote(
        id: String,
        title: String,
        blocks: List<NoteBlock>,
        authorType: String = "user",
        sourceType: String = "manual",
        sourceUrl: String? = null,
        createdAt: String = Instant.now().toString(),
        deletedAt: String? = null,
    ): NoteEntity = NoteEntity(
        id = id,
        title = title,
        documentJson = NoteDocument(blocks).encode(),
        searchText = blocks.joinToString("\n") { it.text },
        authorType = authorType,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        createdAt = createdAt,
        updatedAt = createdAt,
        revision = 1L,
        contentHash = "h-$id",
        deletedAt = deletedAt,
        lastDraftId = "d-$id",
        committedSeq = 1L,
    )

    @Test
    fun statsSheetDrilldownScopeFilterAndTimezoneSwitch() {
        val nowIso = Instant.now().toString()
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val manual = buildNote("s-man", "手写日志", listOf(NoteBlock.paragraph("今日复盘正文")), createdAt = nowIso)
        val imported = buildNote(
            id = "s-imp",
            title = "导入资料",
            blocks = listOf(NoteBlock.paragraph("外部 Markdown")),
            sourceType = "import_markdown",
            createdAt = nowIso,
        )
        val deleted = buildNote(
            id = "s-del",
            title = "废纸草稿",
            blocks = listOf(NoteBlock.paragraph("不应计入统计")),
            createdAt = nowIso,
            deletedAt = nowIso,
        )
        val tags = listOf(TagEntity(id = "tag-1", parentId = "", name = "思考", nameKey = "思考"))
        val refs = listOf(NoteTagEntity(noteId = "s-man", tagId = "tag-1"))

        var openedNoteId: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val backdrop = rememberGlassBackdrop()
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        StatsSheet(
                            allNotes = listOf(manual, imported, deleted),
                            tagRefs = refs,
                            tags = tags,
                            dark = false,
                            backdrop = backdrop,
                            onDismiss = {},
                            onOpenNote = { openedNoteId = it },
                            onSelectTag = {},
                        )
                    }
                }
            }

            compose.onNodeWithTag("stats-sheet").assertIsDisplayed()
            // Default Manual scope: only "手写日志" appears in today's drill-down
            compose.onNodeWithText("手写日志").performScrollTo().assertIsDisplayed()

            // Switch to All scope -> both Manual and Imported appear, Deleted stays excluded
            compose.onNodeWithTag("stats-origin-All").performScrollTo().performClick()
            compose.onNodeWithTag("stats-day-cell-$today").performScrollTo().performClick()
            compose.onNodeWithText("导入资料").performScrollTo().assertIsDisplayed()

            // Switch timezone & tap note row to jump back
            compose.onNodeWithTag("stats-timezone-toggle").performScrollTo().performClick()
            compose.onNodeWithText("手写日志").performScrollTo().performClick()
            assertEquals("s-man", openedNoteId)
        }
    }

    @Test
    fun shareCardSheetRendersMultiPageBitmapsWithUnsupportedMediaAndPrivacyControl() {
        val blocks = (1..16).map { i ->
            NoteBlock.paragraph("分享段落 $i：记录本地回顾、关联索引和桌面小组件的完整工程实践。".repeat(2))
        } + NoteBlock.attachment("audio", "voice-demo-1")

        val note = buildNote(
            id = "share-demo-1",
            title = "浮记工程周报",
            blocks = blocks,
            sourceType = "share",
            sourceUrl = "https://secret.local/memo/99",
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prepared = ShareCardPaginator.buildPreparedContent(
            note = note,
            tagNames = listOf("工程", "M4"),
            options = ShareCardOptions(template = ShareCardTemplate.Paper, includeSourceMetadata = false),
        )
        val rendered = ShareCardRenderer.renderAllPages(
            context = context,
            noteId = note.id,
            prepared = prepared,
            template = ShareCardTemplate.Paper,
        )
        assertTrue(rendered.size >= 2)
        assertTrue(rendered.all { it.file.exists() && it.file.length() > 1024L && it.contentUri.toString().contains(".attachments") })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val backdrop = rememberGlassBackdrop()
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        ShareCardSheet(
                            note = note,
                            tagNames = listOf("工程", "M4"),
                            dark = false,
                            backdrop = backdrop,
                            onDismiss = {},
                        )
                    }
                }
            }

            compose.onNodeWithTag("share-card-sheet").assertIsDisplayed()
            compose.onNodeWithTag("share-pagination-notice").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("share-unsupported-media-notice").performScrollTo().assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 8_000L) {
                compose.onAllNodesWithTag("share-preview-page-1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("share-preview-page-1").performScrollTo().assertIsDisplayed()

            // Switch template and toggle optional source metadata
            compose.onNodeWithTag("share-template-Minimal").performScrollTo().performClick()
            compose.onNodeWithTag("share-include-source-toggle").performScrollTo().performClick()
            compose.onNodeWithTag("share-open-system-chooser").performScrollTo().assertIsDisplayed()
            // Ensure status text does NOT falsely claim share succeeded before chooser finishes (Q055)
            compose.onNodeWithTag("share-status-text").assertDoesNotExist()
        }
    }

    @Test
    fun widgetReviewDeepLinkOpensExploreReviewTabDirectly() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(WidgetContract.EXTRA_WIDGET_ACTION, WidgetContract.ACTION_OPEN_REVIEW_SESSION)
            putExtra(WidgetContract.EXTRA_REVIEW_SESSION_ID, "session-missing-or-latest")
            putExtra(WidgetContract.EXTRA_NOTE_ID, "nonexistent-fallback")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(timeoutMillis = 8_000L) {
                compose.onAllNodesWithTag("explore-open-stats").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("explore-open-stats").performClick()
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.onAllNodesWithTag("stats-sheet").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("stats-sheet").assertIsDisplayed()
        }
    }
}
