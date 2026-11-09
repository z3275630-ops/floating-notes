package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LocalRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun saveMixedDocumentReopenAndAutosaveEdit() {
        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").performTextReplacement("本机记录验证")
        compose.onNodeWithText("添加待办").performClick()
        compose.onNodeWithTag("block-editor-1").performTextReplacement("读完这一页")
        compose.onNodeWithTag("checkbox-1").performClick().assertIsOn()
        compose.onNodeWithText("保存").performClick()
        val card = hasTestTag("saved-note") and hasText("本机记录验证", substring = true)
        await(card)
        compose.activityRule.scenario.recreate()
        await(card)
        compose.onNode(card).performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").assertTextEquals("本机记录验证")
        compose.onNodeWithTag("block-editor-1").assertTextEquals("读完这一页")
        compose.onNodeWithTag("checkbox-1").assertIsOn()
        compose.onNodeWithTag("capture-editor").performTextReplacement("编辑已自动保存")
        await(hasText("已保存到本机"))
        compose.onNodeWithText("关闭").performClick()
        await(hasTestTag("saved-note") and hasText("编辑已自动保存", substring = true))
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
}
