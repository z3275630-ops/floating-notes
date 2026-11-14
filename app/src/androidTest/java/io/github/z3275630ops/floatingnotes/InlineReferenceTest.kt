package io.github.z3275630ops.floatingnotes

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class InlineReferenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun referenceJsonRoundTripPreservesTargetBlockAndUtf16Range() {
        val block = NoteBlock.reference(
            noteId = "00000000-0000-4000-8000-000000000001",
            revision = 3L,
            label = "目标笔记 · “摘录”",
            targetBlockId = "00000000-0000-4000-8000-000000000002",
            start = 2,
            end = 6,
        )
        val encoded = NoteDocument(listOf(block)).encode()
        val decoded = NoteDocument.decode(encoded).blocks.single()
        assertEquals("00000000-0000-4000-8000-000000000001", decoded.referenceTarget)
        assertEquals(3L, decoded.referenceRevision)
        assertEquals("00000000-0000-4000-8000-000000000002", decoded.referenceTargetBlockId)
        assertEquals(2, decoded.referenceStart)
        assertEquals(6, decoded.referenceEnd)
    }

    @Test
    fun inlineHashTagCreationAndExcerptReferenceFlow() {
        val suffix = UUID.randomUUID().toString().take(8)
        val sourceTitle = "参考源笔记-$suffix"
        val draftTitle = "周报-$suffix"
        val tagName = "冲刺-$suffix"

        // 1. Create a source note to quote later.
        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        compose.onNodeWithTag("capture-editor").performTextReplacement("$sourceTitle\n第二句关键内容😀测试")
        compose.onNodeWithText("保存").performClick()
        await(hasTestTag("saved-note") and hasText(sourceTitle, substring = true))

        // 2. Open a new draft, attach a tag via inline '#' suggestion bar.
        compose.onNodeWithTag("new-note").performClick()
        await(hasTestTag("capture-editor"))
        val editor = compose.onNodeWithTag("capture-editor")
        editor.performClick()
        editor.performTextInput("$draftTitle #$tagName")
        await(hasTestTag("inline-tag-suggestions"))
        compose.onNodeWithText("创建 #$tagName").performClick()
        await(hasText("#$tagName"))

        // 3. Open '@ 引用', choose excerpt range on target note, insert excerpt, and save.
        compose.onNodeWithText("@ 引用").performClick()
        compose.onNodeWithTag("note-reference-filter").performTextReplacement(sourceTitle)
        compose.onNodeWithText("选段").performClick()
        await(hasText("选择引用选区范围"))
        compose.onNodeWithText("插入引用片段").performClick()
        compose.onNodeWithText("保存").performClick()
        await(hasTestTag("saved-note") and hasText(draftTitle, substring = true))
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(10_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
}
