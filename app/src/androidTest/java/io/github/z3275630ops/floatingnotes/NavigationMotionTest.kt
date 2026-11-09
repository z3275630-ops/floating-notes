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
        compose.onNodeWithTag("capture-editor").performClick().performTextInput("留下这段草稿")
        compose.onNodeWithTag("capture-handle").performTouchInput {
            swipe(center, center + Offset(0f, 460f), durationMillis = 350)
        }
        compose.onNodeWithTag("new-note").assertIsDisplayed().performClick()
        compose.onNodeWithTag("capture-editor").assertTextEquals("留下这段草稿")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("tab-Record").assertIsSelected()
    }
}
