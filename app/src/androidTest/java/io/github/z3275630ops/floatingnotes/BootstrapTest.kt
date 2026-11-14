package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class BootstrapTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchAndLoadSyntheticData() {
        compose.onNodeWithText("浮记").assertExists()
        compose.onNodeWithTag("open-notes-drawer").performClick()
        compose.onNodeWithTag("notes-drawer-list").performScrollToNode(hasTestTag("drawer-dev-section"))
        if (compose.onAllNodes(hasText("载入合成示例")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("drawer-dev-section").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("载入合成示例")).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithText("载入合成示例").performScrollTo().performClick()
        val sampleText = "【合成示例】读书时想到：先把自己的想法记下来，明天再看看。"
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("new-note")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction() and !hasTestTag("notes-drawer-list"))
            .performScrollToNode(hasText(sampleText))
        compose.onAllNodesWithText(sampleText).onFirst().assertExists()
    }
}
