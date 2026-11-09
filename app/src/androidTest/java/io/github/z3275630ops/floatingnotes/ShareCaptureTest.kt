package io.github.z3275630ops.floatingnotes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.feature.share.ShareCaptureActivity
import io.github.z3275630ops.floatingnotes.feature.share.SharePayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
class ShareCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = NotesRepository(NotesDatabase.open(context))

    @Test fun bothSystemActionsResolveToTheCaptureActivity() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT)) {
            val intent = Intent(action).setType("text/plain").setPackage(context.packageName)
            @Suppress("DEPRECATION")
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(matches.any {
                it.activityInfo.name == ShareCaptureActivity::class.java.name && it.activityInfo.exported
            })
        }
    }

    @Test fun shareLinkRecreateThenSaveTwiceAsSeparateSessions() {
        val url = "https://example.com/${UUID.randomUUID()}"
        val text = "合成分享摘录 $url"
        repeat(2) { index ->
            val scenario = ActivityScenario.launch<ShareCaptureActivity>(incoming(Intent.ACTION_SEND, url))
            try {
                await(hasTestTag("capture-editor"))
                compose.onNodeWithText("来源链接 · $url").assertIsDisplayed()
                compose.onNodeWithTag("capture-editor").performClick().performTextReplacement(text)
                scenario.recreate()
                await(hasTestTag("capture-editor"))
                compose.onNodeWithTag("capture-editor").assertTextEquals(text)
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(10_000) {
                    runBlocking { repository.notes.first().count { it.searchText == text } == index + 1 }
                }
                val matches = runBlocking { repository.notes.first().filter { it.searchText == text } }
                assertTrue(matches.all { it.sourceType == "share" && it.sourceUrl == url && it.revision == 1L })
                assertEquals(index + 1, matches.map { it.id }.toSet().size)
            } finally {
                scenario.close()
            }
        }
    }

    @Test fun cancelProcessedTextKeepsManualDraft() {
        val original = runBlocking {
            val draft = repository.loadDraft("manual") ?: repository.newDraft("manual").copy(
                editSeq = 1,
                documentJson = NoteDocument(listOf(NoteBlock.paragraph("原有合成草稿"))).encode(),
            )
            repository.checkpoint(draft)
            draft
        }
        val raw = "来源应用的选区"
        val scenario = ActivityScenario.launch<ShareCaptureActivity>(incoming(Intent.ACTION_PROCESS_TEXT, raw))
        try {
            await(hasTestTag("capture-editor"))
            compose.onNodeWithTag("capture-editor").assertTextEquals(raw)
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10_000) {
                runBlocking {
                    repository.captureDrafts.first().none { NoteDocument.decode(it.documentJson).text == raw }
                }
            }
            assertEquals(original, runBlocking { repository.loadDraft("manual") })
            assertTrue(runBlocking { repository.notes.first().none { it.searchText == raw } })
        } finally {
            scenario.close()
            runBlocking { repository.discard(original) }
        }
    }

    @Test fun rejectEmptyOversizeAndNonTextWithoutGuessingSource() {
        assertTrue(SharePayload.read(incoming(Intent.ACTION_SEND, " ")) is SharePayload.Rejected)
        assertTrue(SharePayload.read(incoming(Intent.ACTION_SEND, "字".repeat(100_001))) is SharePayload.Rejected)
        assertTrue(SharePayload.read(incoming(Intent.ACTION_SEND, "内容").setType("image/png")) is SharePayload.Rejected)
        val malformed = incoming(Intent.ACTION_SEND, "").putExtra(Intent.EXTRA_TEXT, 9)
        assertTrue(SharePayload.read(malformed) is SharePayload.Rejected)
        val mixed = SharePayload.read(incoming(Intent.ACTION_SEND, "正文含 https://example.com"))
        assertEquals(SharePayload.Text("正文含 https://example.com", null), mixed)
    }

    private fun incoming(action: String, text: String): Intent =
        Intent(action).setClass(context, ShareCaptureActivity::class.java).setType("text/plain")
            .putExtra(if (action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT, text)

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
}
