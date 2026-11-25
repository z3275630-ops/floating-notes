package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EditorSwipeSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun blankCanvasTapCreatesNewParagraphInExistingNote() {
        val suffix = UUID.randomUUID().toString().take(8)
        val firstLine = "合成首行文字-$suffix"
        val secondLine = "合成点空白新行-$suffix"

        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").performTextReplacement(firstLine)
        compose.onNodeWithText("保存").performClick()

        val card = hasTestTag("saved-note") and hasText(firstLine, substring = true)
        await(card)
        compose.onNode(card).performClick()
        await(hasTestTag("capture-editor"))
        await(hasTestTag("editor-blank-canvas"))

        compose.onNodeWithTag("editor-blank-canvas").performClick()
        await(hasTestTag("block-editor-1"))
        compose.onNodeWithTag("block-editor-1").performTextReplacement(secondLine)
        await(hasText("已保存到本机"))

        compose.onNodeWithText("关闭").performClick()
        await(hasTestTag("saved-note") and hasText(secondLine, substring = true))
        moveCardToTrash(firstLine)
    }

    @Test
    fun swipeRightOnTextFieldEntersBlockSelectionMode() {
        val suffix = UUID.randomUUID().toString().take(8)
        val bodyLine = "合成右滑选择正文-$suffix"

        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").performTextReplacement(bodyLine)
        compose.onNodeWithTag("editor-blank-canvas").performClick()
        await(hasTestTag("block-editor-1"))
        compose.onNodeWithTag("block-editor-1").performTextReplacement("第二段对照-$suffix")
        compose.onNodeWithText("保存").performClick()

        val card = hasTestTag("saved-note") and hasText(bodyLine, substring = true)
        await(card)
        compose.onNode(card).performClick()
        await(hasTestTag("capture-editor"))

        compose.onNodeWithTag("capture-editor").performTouchInput {
            swipeRight(
                startX = width * 0.18f,
                endX = width * 0.72f,
                durationMillis = 180,
            )
        }

        await(hasText("已选择 1 段"))
        compose.onNodeWithText("转待办").assertIsDisplayed()
        compose.onNodeWithText("全选").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed()

        compose.onNodeWithTag("block-selection-done").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("已选择 1 段")).fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithText("关闭").performClick()
        await(card)
        moveCardToTrash(bodyLine)
    }

    private fun moveCardToTrash(snippet: String) {
        val card = hasTestTag("saved-note") and hasText(snippet, substring = true)
        await(card)
        val actions = hasTestTag("note-actions") and hasAnyAncestor(card)
        compose.onNode(actions).performClick()
        compose.onNodeWithTag("note-menu").assertIsDisplayed()
        compose.onNodeWithText("移到废纸篓").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(card).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
}
