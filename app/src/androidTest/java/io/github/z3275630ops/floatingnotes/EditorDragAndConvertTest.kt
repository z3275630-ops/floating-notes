package io.github.z3275630ops.floatingnotes

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.feature.capture.EditorBlock
import io.github.z3275630ops.floatingnotes.feature.capture.EditorSession
import io.github.z3275630ops.floatingnotes.feature.capture.NoteEditor
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorDragAndConvertTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var modelWithTestDraft: NotesViewModel? = null

    @Before
    fun ensureTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @After
    fun discardTestDraft() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val model = modelWithTestDraft ?: return
        runBlocking { withContext(Dispatchers.Main) { assertTrue(model.finishEditor(discard = true)) } }
    }

    @Test
    fun longPressDragMovesTheFirstBlockPastTheThird() {
        val session = openEditor("拖动第一段", "拖动第二段", "拖动第三段", "拖动第四段")
        val ids = compose.runOnIdle { session.blocks.map { it.id } }
        dragBlock(from = 0, to = 2, blockCount = 4)

        editor(0).assertTextContains("拖动第二段")
        editor(1).assertTextContains("拖动第三段")
        editor(2).assertTextContains("拖动第一段")
        editor(3).assertTextContains("拖动第四段")
        compose.runOnIdle { assertEquals(listOf(ids[1], ids[2], ids[0], ids[3]), session.blocks.map { it.id }) }
    }

    @Test
    fun draggingHeadingAndParagraphsThroughFirstThreeSlotsKeepsCompactSpacing() {
        val heading = NoteBlock.heading(level = 2, text = "章节标题")
        val first = NoteBlock.paragraph("甲段正文内容示例")
        val second = NoteBlock.paragraph("乙段正文内容示例")
        val third = NoteBlock.paragraph("丙段正文内容示例")
        val session = openEditor(NoteDocument(listOf(heading, first, second, third)))
        val headingHeight = fieldBounds(0).height
        val paragraphHeight = fieldBounds(1).height
        val pixelTolerance = with(compose.density) { 1.dp.toPx() }
        val rowMinHeight = with(compose.density) { 28.dp.toPx() }
        val fieldVerticalPadding = with(compose.density) { 2.dp.toPx() }

        // The field's bounds exclude its padding and any centering inside the minimum-height row.
        fun inset(bounds: Rect): Float = maxOf(fieldVerticalPadding, (rowMinHeight - bounds.height) / 2)

        fun assertLayout(vararg expected: NoteBlock) {
            compose.runOnIdle { assertEquals(expected.toList(), session.document().blocks) }
            val bounds = expected.indices.map { index ->
                editor(index).assertIsDisplayed().assertIsNotFocused().assertTextContains(expected[index].text)
                assertEquals("Line count at slot $index", 1, fieldLineCount(index))
                fieldBounds(index).also {
                    val expectedHeight = if (expected[index] == heading) headingHeight else paragraphHeight
                    assertEquals("Text field height at slot $index", expectedHeight, it.height, pixelTolerance)
                }
            }
            bounds.zipWithNext().forEachIndexed { index, (above, below) ->
                val gap = below.top - above.bottom
                val nextToHeading = expected[index] == heading || expected[index + 1] == heading
                val spacingDp = (gap - inset(above) - inset(below)) / pixelTolerance
                val allowed = if (nextToHeading) 5f..8f else 2f..4f
                assertTrue("Gap after slot $index was ${gap}px (spacing ${spacingDp}dp)", gap >= 0f && spacingDp in allowed)
            }
        }

        assertLayout(heading, first, second, third)
        dragBlock(from = 0, to = 1, blockCount = 4)
        assertLayout(first, heading, second, third)
        dragBlock(from = 1, to = 2, blockCount = 4)
        assertLayout(first, second, heading, third)
        dragBlock(from = 0, to = 2, blockCount = 4)
        assertLayout(second, heading, first, third)
        dragBlock(from = 1, to = 0, blockCount = 4)
        assertLayout(heading, second, first, third)
    }

    @Test
    fun showingAndHidingParagraphControlsKeepsTextWidthAndHeight() {
        val text = "这段合成正文用于验证显示段落操作后文字仍然在相同位置换行。"
        openEditor(text, "另一段正文")
        val before = fieldBounds(0)
        val lines = fieldLineCount(0)
        val pitch = fieldBounds(1).top - before.top

        editor(0).performTouchInput { click() }.assertIsFocused()
        compose.onNodeWithTag("block-actions-0").assertIsDisplayed()
        assertEquals(before.width, fieldBounds(0).width, 1f)
        assertEquals(before.height, fieldBounds(0).height, 1f)
        assertEquals(pitch, fieldBounds(1).top - fieldBounds(0).top, 1f)
        assertEquals(lines, fieldLineCount(0))

        editor(1).performTouchInput { click() }.assertIsFocused()
        compose.onNodeWithTag("block-actions-0").assertDoesNotExist()
        editor(0).assertIsNotFocused()
        assertEquals(before.width, fieldBounds(0).width, 1f)
        assertEquals(before.height, fieldBounds(0).height, 1f)
        assertEquals(pitch, fieldBounds(1).top - fieldBounds(0).top, 1f)
        assertEquals(lines, fieldLineCount(0))
    }

    @Test
    fun contextMenuConvertsFocusedParagraphToHeadingAndTodoKeepingSelection() {
        val text = "保留原文和选区"
        val session = openEditor(text)
        val field = editor(0)
        field.performTouchInput { click() }
        field.performTextInputSelection(TextRange(2, 4))
        val selection = SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 4))

        openBlockMenu(0)
        field.assertIsFocused()
        choose("转化为一级标题 (H1)")
        field.assertTextContains(text).assertIsFocused().assert(selection)
        compose.runOnIdle {
            val block = session.blocks.single().documentBlock()
            assertEquals("heading", block.type)
            assertEquals(1, block.level)
        }

        openBlockMenu(0)
        compose.onNodeWithText("转化为一级标题 (H1)").assertDoesNotExist()
        field.assertIsFocused()
        choose("转化为待办事项")
        compose.onNodeWithTag("checkbox-0").assertIsDisplayed()
        field.assertTextContains(text).assertIsFocused().assert(selection)

        compose.onNodeWithContentDescription("撤销").performClick()
        compose.onNodeWithTag("checkbox-0").assertDoesNotExist()
        field.assertTextContains(text).assertIsFocused().assert(selection)
        compose.onNodeWithContentDescription("重做").performClick()
        compose.onNodeWithTag("checkbox-0").assertIsDisplayed()
        field.assertTextContains(text).assertIsFocused().assert(selection)
    }

    @Test
    fun convertingAnUnfocusedBlockDoesNotRestartEditing() {
        openEditor("保持未聚焦的第一段", "第二段")
        editor(0).performClick()
        hideKeyboard()
        editor(0).assertIsNotFocused()
        editor(1).assertIsNotFocused()

        openBlockMenu(0)
        choose("转化为待办事项")

        compose.onNodeWithTag("checkbox-0").assertIsDisplayed()
        editor(0).assertTextContains("保持未聚焦的第一段").assertIsNotFocused()
        editor(1).assertTextContains("第二段").assertIsNotFocused()
    }

    @Test
    fun insertingTodoAfterHidingKeyboardFollowsSelectedBlock() {
        val session = openEditor("第一段", "第二段")
        editor(0).performClick().assertIsFocused()
        hideKeyboard()
        editor(0).assertIsNotFocused()

        compose.onNodeWithText("添加待办").performClick()

        compose.onNodeWithTag("checkbox-1").assertIsDisplayed()
        editor(2).assertTextContains("第二段")
        compose.runOnIdle {
            assertEquals(listOf("paragraph", "checkbox", "paragraph"), session.blocks.map { it.type })
        }
    }

    @Test
    fun tappingSaveOutsideTheInsertMenuOnlyDismissesItOnTheFirstTap() {
        var saves = 0
        openEditor(NoteDocument(listOf(NoteBlock.paragraph("正文"))), onSave = { saves++ })
        editor(0).performTouchInput { click() }.assertIsFocused()
        openMoreInsertMenu()
        val menu = compose.onNodeWithTag("editor-more-insert-menu").assertIsDisplayed()
        editor(0).assertIsFocused()

        val save = compose.onNodeWithText("保存")
        tapInWindow(save)
        menu.assertDoesNotExist()
        editor(0).assertIsFocused()
        compose.runOnIdle { assertEquals(0, saves) }

        tapInWindow(save)
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun touchKeepsEditingFocusAndKeyboardCanActivateTodoAndFoldControls() {
        val session = openEditor(
            NoteDocument(listOf(NoteBlock.checkbox("检查待办"), NoteBlock.toggle(title = "折叠标题", collapsed = true))),
            attachToModel = true,
        )
        val checkbox = compose.onNodeWithTag("checkbox-0")
        editor(0).performTouchInput { click() }.assertIsFocused()
        tapInWindow(checkbox)
        editor(0).assertIsFocused()
        compose.runOnIdle { assertTrue(session.blocks[0].checked) }

        // A real Tab event switches Android out of touch mode.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        checkbox.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        checkbox.performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertEquals(false, session.blocks[0].checked) }
        checkbox.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(session.blocks[0].checked) }

        val disclosure = compose.onNodeWithContentDescription("展开折叠块")
        disclosure.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        disclosure.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("收起折叠块").assertIsFocused()
        compose.runOnIdle { assertEquals(false, session.blocks[1].documentBlock().collapsed) }
    }

    @Test
    fun multiSelectPreselectsTheLastEditedBlockAfterHidingTheKeyboard() {
        openEditor("第一段", "第二段")
        editor(1).performTouchInput { click() }.assertIsFocused()
        hideKeyboard()
        editor(1).assertIsNotFocused()

        openMoreInsertMenu()
        choose("多选整理段落")

        compose.onNodeWithText("已选择 1 段").assertIsDisplayed()
        val selected = compose.onNodeWithContentDescription("取消选择该段").fetchSemanticsNode().boundsInRoot
        assertTrue(selected.center.y > fieldBounds(0).bottom)
        val second = fieldBounds(1)
        assertTrue(second.center.y in selected.top..selected.bottom)
    }

    private fun openEditor(vararg lines: String): EditorSession =
        openEditor(NoteDocument(lines.map { NoteBlock.paragraph(it) }))

    private fun openEditor(
        document: NoteDocument,
        onSave: (() -> Unit)? = null,
        attachToModel: Boolean = false,
    ): EditorSession {
        val id = UUID.randomUUID().toString()
        val draft = DraftEntity(
            id = id,
            slot = "editor-interaction-test",
            noteId = id,
            baseRevision = 0,
            documentJson = document.encode(),
            editSeq = 0,
            updatedAt = Instant.EPOCH.toString(),
        )
        lateinit var model: NotesViewModel
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[NotesViewModel::class.java]
        }
        val session = if (attachToModel) {
            // The task toggle checks session ownership. Give it a separate, disposable capture.
            runBlocking {
                withContext(Dispatchers.Main) {
                    model.openIncomingCapture(id, "编辑器回归", sourceUrl = null)
                    val attached = requireNotNull(model.editor).also { check(it.draft.id == id) }
                    modelWithTestDraft = model
                    attached.blocks.clear()
                    attached.blocks.addAll(document.blocks.map(::EditorBlock))
                    attached
                }
            }
        } else {
            EditorSession(draft, document, existingNote = false)
        }
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().imePadding()) {
                    if (onSave != null) TextButton(onClick = onSave) { Text("保存") }
                    NoteEditor(session = session, model = model, onSave = onSave ?: {}, onDiscard = {})
                }
            }
        }
        return session
    }

    private fun editor(index: Int): SemanticsNodeInteraction =
        compose.onNodeWithTag(if (index == 0) "capture-editor" else "block-editor-$index")

    private fun fieldBounds(index: Int): Rect = editor(index).fetchSemanticsNode().boundsInRoot

    private fun fieldLineCount(index: Int): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        editor(index).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().lineCount
    }

    private fun dragBlock(from: Int, to: Int, blockCount: Int) {
        editor(from).performTouchInput { click() }.assertIsFocused()
        val handle = compose.onNodeWithContentDescription("长按拖动调整段落顺序")
        val startY = handle.fetchSemanticsNode().boundsInRoot.center.y
        val targetY = fieldBounds(to).center.y
        val nextIndex = to + if (to > from) 1 else -1
        val endY = if (nextIndex in 0 until blockCount) {
            (targetY + fieldBounds(nextIndex).center.y) / 2
        } else {
            fieldBounds(to).top
        }
        handle.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center)
            for (step in 1..12) {
                moveTo(center + Offset(0f, (endY - startY) * step / 12))
            }
            up()
        }
    }

    private fun hideKeyboard() =
        compose.onNodeWithContentDescription("收起键盘").performClick()

    private fun openBlockMenu(index: Int) {
        compose.onNodeWithTag("block-actions-$index").performTouchInput { click() }
        compose.onNodeWithTag("block-context-menu").assertIsDisplayed()
    }

    private fun openMoreInsertMenu() {
        compose.onNodeWithContentDescription("更多插入").performScrollTo().performTouchInput { click() }
    }

    private fun choose(label: String) = compose.onNodeWithText(label).performTouchInput { click() }

    private fun tapInWindow(target: SemanticsNodeInteraction) {
        val node = target.fetchSemanticsNode()
        val point = node.positionOnScreen + node.boundsInRoot.center - node.positionInRoot
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        // Compose's touch injection dispatches directly to the node's root and skips popup window hit testing.
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }
}
