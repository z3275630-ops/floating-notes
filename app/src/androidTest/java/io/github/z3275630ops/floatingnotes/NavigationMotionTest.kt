package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun switchTabsAndDismissCaptureByDragWithoutLosingDraft() {
        for (tab in listOf("Explore", "Agent", "Library", "Record")) {
            compose.onNodeWithTag("tab-$tab").performClick().assertIsSelected()
        }
        compose.onNodeWithTag("new-note").performClick()
        waitForEditor()
        compose.onNodeWithTag("capture-editor").performClick().performTextReplacement("留下这段草稿")
        compose.onNodeWithTag("capture-handle").performTouchInput {
            swipe(center, center + Offset(0f, 800f), durationMillis = 350)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("new-note").assertIsDisplayed().performClick()
        waitForEditor()
        compose.onNodeWithTag("capture-editor").assertTextEquals("留下这段草稿")
        compose.onNodeWithText("丢弃草稿").performClick()
        compose.onNodeWithText("丢弃").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab-Record").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-Record").assertIsSelected()
    }

    private fun waitForEditor() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("capture-editor").fetchSemanticsNodes().isNotEmpty()
    }
}
