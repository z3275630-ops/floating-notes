package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class LocalRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun saveMixedDocumentReopenAndAutosaveEdit() {
        await(hasTestTag("new-note"))
        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").performTextReplacement("本机记录验证")
        compose.onNodeWithText("添加待办").performClick()
        compose.onNodeWithTag("block-editor-1").performTextReplacement("读完这一页")
        compose.onNodeWithTag("checkbox-1").performClick().assertIsOn()
        compose.onNodeWithText("保存").performClick()
        val card = hasTestTag("saved-note") and hasText("本机记录验证", substring = true)
        await(card)
        val actions = hasTestTag("note-actions") and hasAnyAncestor(card)
        compose.onNode(actions).performClick()
        compose.onNodeWithTag("note-menu").assertIsDisplayed()
        compose.onNodeWithText("置顶").performClick()
        await(card and hasText("已置顶"))
        compose.onNode(actions).performClick()
        compose.onNodeWithText("取消置顶").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(card and hasText("已置顶")).fetchSemanticsNodes().isEmpty()
        }
        compose.activityRule.scenario.recreate()
        await(card)
        compose.onNode(card).performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").assertTextEquals("本机记录验证")
        compose.onNodeWithTag("block-editor-1").assertTextEquals("读完这一页")
        compose.onNodeWithTag("checkbox-1").assertIsOn()
        compose.onNodeWithTag("note-start-editing").performClick()
        await(hasTestTag("note-writing"))
        compose.onNodeWithTag("capture-editor").performTextReplacement("编辑已自动保存")
        await(hasText("已保存到本机"))
        compose.onNodeWithTag("note-done").performClick()
        await(hasTestTag("note-reading"))
        compose.onNodeWithTag("note-page-back").performClick()
        await(hasTestTag("saved-note") and hasText("编辑已自动保存", substring = true))
    }

    @Test fun insertReferenceAndOpenItsBacklink() {
        val suffix = UUID.randomUUID().toString().take(8)
        val target = "待引用的测试笔记-$suffix"
        val source = "带引用的测试笔记-$suffix"
        val targetCard = hasTestTag("saved-note") and hasText(target)
        val sourceCard = hasTestTag("saved-note") and hasText(source, substring = true)
        await(hasTestTag("new-note"))
        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").performTextReplacement(target)
        compose.onNodeWithText("保存").performClick()
        await(targetCard)
        await(hasTestTag("new-note"))
        compose.onNodeWithTag("new-note").performClick()
        await(hasText("@ 引用"))
        compose.onNodeWithTag("capture-editor").performTextReplacement(source)
        compose.onNodeWithText("@ 引用").performClick()
        val filter = compose.onNodeWithTag("note-reference-filter")
        filter.performTextReplacement("无匹配测试词")
        compose.onNode(hasText(target) and !hasTestTag("editor-edge-echo")).assertDoesNotExist()
        compose.onNodeWithContentDescription("清除筛选").performClick()
        compose.onNode(hasText(target) and !hasTestTag("editor-edge-echo")).assertExists()
        filter.performTextReplacement(target)
        compose.onNode(hasText(target) and hasClickAction() and !hasTestTag("note-reference-filter") and !hasTestTag("editor-edge-echo")).performClick()
        compose.onNodeWithText("保存").performClick()
        await(sourceCard)
        compose.onNode(targetCard).performClick()
        await(hasText("反链 1"))
        compose.onNodeWithText("反链 1").performClick()
        compose.onNode(hasText(source) and !hasTestTag("editor-edge-echo")).performClick()
        await(hasText(source))
        compose.onNodeWithTag("capture-editor").assertTextEquals(source)
        compose.onNodeWithTag("note-page-back").performClick()
        await(hasTestTag("new-note"))
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
}
