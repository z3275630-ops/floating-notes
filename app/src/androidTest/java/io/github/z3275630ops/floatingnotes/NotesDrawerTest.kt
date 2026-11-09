package io.github.z3275630ops.floatingnotes

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NotesDrawerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun drawerFiltersByNotebookAndReturnsToBottomNavigation() {
        val suffix = UUID.randomUUID().toString().take(6)
        val included = "项目记录 $suffix"
        val excluded = "其他记录 $suffix"
        val notebookName = "项目 $suffix"
        val db = NotesDatabase.open(compose.activity.application)
        runBlocking {
            val notes = NotesRepository(db)
            val organization = OrganizationRepository(db)
            suspend fun save(text: String) = notes.commit(notes.newDraft(text).copy(
                documentJson = NoteDocument(listOf(NoteBlock.paragraph(text))).encode(), editSeq = 1,
            ))
            save(excluded)
            val saved = save(included)
            val notebook = organization.createNotebook(notebookName)
            organization.fileNote(saved.id, notebook.id)
        }
        val includedCard = hasTestTag("saved-note") and hasText(included)
        val excludedCard = hasTestTag("saved-note") and hasText(excluded)
        await(includedCard)
        screenshot("mint-home")
        compose.onNodeWithTag("open-notes-drawer").performClick()
        val notebook = hasTestTag("drawer-notebook") and hasText(notebookName)
        await(notebook)
        compose.onNode(notebook).assertIsDisplayed()
        screenshot("mint-drawer")
        compose.onNode(notebook).performClick()
        await(hasTestTag("tab-Library"))
        compose.onNodeWithTag("tab-Library").assertIsSelected()
        await(includedCard)
        compose.onNode(excludedCard).assertDoesNotExist()
        compose.onNodeWithTag("open-notes-drawer").performClick()
        await(hasTestTag("drawer-archive"))
        compose.onNodeWithTag("drawer-archive").performClick()
        await(hasText("归档"))
        compose.onNodeWithTag("tab-Record").assertIsSelected()
        compose.onNodeWithTag("open-notes-drawer").performClick()
        await(hasTestTag("drawer-all"))
        compose.onNodeWithTag("notes-drawer").performTouchInput { swipeLeft() }
        await(hasTestTag("tab-Record"))
        compose.onNodeWithTag("tab-Record").assertIsSelected()
        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        screenshot("mint-capture")
        compose.onNodeWithText("关闭").performClick()
        await(hasTestTag("tab-Record"))
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
