package io.github.z3275630ops.floatingnotes.feature.capture

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.text.TextRange
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.NoteMarkType
import io.github.z3275630ops.floatingnotes.core.model.NoteTextMark
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EditorDragAndFocusUnitTest {
    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun absolutePointerCoordinatesAreInvariantUnderMovingHandleAndAutoScroll() {
        val drag = BlockDragController().apply { listTopInRootPx = 80f }
        val handleOrigin = 100f
        val grabY = 14f
        drag.start("a", 0, handleOrigin + grabY, handleOrigin)

        drag.updatePointer(174f)
        assertEquals(60f, drag.dragOffsetY, 0f)
        // The next event sees a handle that moved with its layer. Its local Y is again 14.
        drag.updatePointer(handleOrigin + drag.dragOffsetY + grabY)
        assertEquals(60f, drag.dragOffsetY, 0f)
        drag.updateSourceTop("a", handleOrigin - 32f)
        drag.updateSourceTop("a", handleOrigin - 28f)
        assertEquals(88f, drag.dragOffsetY, 0f)
        drag.updatePointer(handleOrigin + drag.dragOffsetY - 28f + grabY)
        assertEquals(88f, drag.dragOffsetY, 0f)
        assertEquals(94f, drag.pointerViewportY, 0f)
    }

    @Test
    fun autoScrollKeepsThePreviewAtTheGrabPointWhenThePinnedRowJumpsBetweenPlacements() {
        val drag = BlockDragController().apply { listTopInRootPx = 80f }
        drag.start("a", 0, 114f, 100f)
        drag.updatePointer(560f)

        // The pointer stays at the edge while more than a viewport scrolls by.
        // Once intervening rows leave the viewport, LazyColumn places the pinned row near its edge.
        for (sourceTop in listOf(100f, -20f, -85f, 42f, 25f, 39f)) {
            drag.updateSourceTop("a", sourceTop)
            assertEquals(560f, sourceTop + drag.dragOffsetY + 14f, 0f)
            assertEquals(480f, drag.pointerViewportY, 0f)
        }
        drag.updateSourceTop("b", 280f)
        drag.updatePointer(530f)
        assertEquals(530f, 39f + drag.dragOffsetY + 14f, 0f)
    }

    @Test
    fun midpointAndViewportPaddingDetermineTheFinalSlot() {
        val drag = BlockDragController().apply { listTopInRootPx = 100f }
        val visible = ids.mapIndexed { index, id -> Item(index, id, index * 40, 40) }
        drag.start("a", 0, 124f, 110f)
        drag.updatePointer(100f + 10f + 99f)
        drag.recomputeDropIndex(ids, visible, -10)
        assertEquals(1, drag.targetDropIndex)
        drag.updatePointer(100f + 10f + 101f)
        drag.recomputeDropIndex(ids, visible, -10)
        assertEquals(0 to 2, drag.finish())
        assertFalse(drag.isDragging)
        assertEquals(0f, drag.dragOffsetY, 0f)
    }

    @Test
    fun scrolledLayoutResolvesStableKeysClampsEdgesAndIgnoresBlankCanvas() {
        val drag = BlockDragController()
        drag.start("a", 0, 15f, 0f)
        drag.updatePointer(400f)
        drag.recomputeDropIndex(
            ids,
            listOf(Item(0, "c", -20, 50), Item(1, "d", 30, 50), Item(2, "editor-blank-canvas", 80, 240)),
            -10,
        )
        assertEquals(0 to 3, drag.finish())

        drag.start("d", 3, 140f, 120f)
        drag.updatePointer(-30f)
        drag.recomputeDropIndex(ids, listOf(Item(0, 0, 0, 40), Item(1, "b", 40, 40)), -10)
        assertEquals(3 to 0, drag.finish())
    }

    @Test
    fun unchangedCanceledOrRemovedDragHasNoMove() {
        val drag = BlockDragController()
        drag.start("b", 1, 45f, 40f)
        drag.recomputeDropIndex(ids, listOf(Item(0, "a", 0, 40), Item(1, "b", 40, 40)), 0)
        assertNull(drag.finish())
        drag.start("a", 0, 10f, 0f)
        drag.cancel()
        assertNull(drag.finish())
        drag.start("a", 0, 10f, 0f)
        drag.recomputeDropIndex(ids.drop(1), emptyList(), 0)
        assertNull(drag.finish())
    }

    @Test
    fun scrollingPastEveryBlockStillDropsBeforeTheBlankCanvas() {
        val drag = BlockDragController()
        drag.start("a", 0, 15f, 0f)
        drag.updatePointer(220f)
        drag.recomputeDropIndex(ids, listOf(Item(4, "editor-blank-canvas", -20, 240)), -10)
        assertEquals(0 to 3, drag.finish())
    }

    @Test
    fun moveBlockUsesTheFinalIndexInBothDirections() {
        val blocks = ids.map { NoteBlock.paragraph(it).copy(id = it) }
        val down = EditorBlockOps.moveBlock(blocks, 0, 2)
        assertEquals(listOf("b", "c", "a", "d"), down.map { it.id })
        assertEquals(blocks, EditorBlockOps.moveBlock(down, 2, 0))
    }

    @Test
    fun reorderedHeadingAndParagraphsSurviveDocumentRoundTrip() {
        val heading = NoteBlock.heading(level = 2, text = "章节标题")
        val first = NoteBlock.paragraph("甲段正文")
        val second = NoteBlock.paragraph("乙段正文")
        val initial = listOf(heading, first, second)

        val moved = EditorBlockOps.moveBlock(initial, 0, 2)
        val reordered = EditorBlockOps.moveBlock(moved, 0, 1)
        assertEquals(listOf(second, first, heading), reordered)

        val document = NoteDocument(reordered)
        val restored = NoteDocument.decode(document.encode())
        assertEquals(reordered.map { it.id }, restored.blocks.map { it.id })
        assertEquals(reordered.map { it.type }, restored.blocks.map { it.type })
        assertEquals(reordered.map { it.text }, restored.blocks.map { it.text })
        assertEquals(heading.level, restored.blocks.last().level)
    }

    @Test
    fun standardConversionsKeepTextMarksAndIdAndDoNotRequestFocus() {
        val original = NoteBlock.paragraph("中文正文", marks = listOf(NoteTextMark(NoteMarkType.BOLD, 0, 2)))
        val before = EditorSnapshot(listOf(original), original.id, 2, selection = TextRange(2, 0))
        val heading = EditorBlockOps.convertFromControls(before, 0, "heading")
        val todo = EditorBlockOps.convertFromControls(before.copy(blocks = heading.blocks), 0, "checkbox")
        val paragraph = EditorBlockOps.convertFromControls(before.copy(blocks = todo.blocks), 0, "paragraph")
        assertEquals("heading", heading.blocks.single().type)
        assertEquals(1, heading.blocks.single().level)
        assertEquals("checkbox", todo.blocks.single().type)
        assertEquals(original, paragraph.blocks.single())
        assertNull(heading.focusBlockId)
        assertNull(todo.focusBlockId)
        assertNull(paragraph.focusBlockId)
        assertNull(EditorBlockOps.convertFromControls(before.copy(focusedBlockId = null), 0, "callout").focusBlockId)
    }

    @Test
    fun branchChangesMapTheSelectionAndDividerSkipsACollapsedBody() {
        val body = NoteBlock.paragraph("标题\n正文")
        val next = NoteBlock.toggle(title = "下一块", collapsed = true)
        val before = EditorSnapshot(listOf(body, next), body.id, 5, selection = TextRange(5, 3))
        val folded = EditorBlockOps.convertFromControls(before, 0, "toggle")
        assertEquals("标题", folded.blocks[0].title)
        assertEquals("正文", folded.blocks[0].text)
        assertEquals(body.id, folded.focusBlockId)
        assertFalse(folded.focusToggleTitle)
        assertEquals(TextRange(2, 0), folded.selection)
        val back = EditorBlockOps.convertFromControls(
            before.copy(blocks = folded.blocks, cursorOffset = 2, selection = folded.selection),
            0,
            "callout",
        )
        assertEquals(body.text, back.blocks[0].text)
        assertEquals(before.selection, back.selection)
        val divider = EditorBlockOps.convertFromControls(before, 0, "divider")
        assertEquals(next.id, divider.focusBlockId)
        assertEquals(true, divider.focusToggleTitle)
    }

    @Test
    fun compositionUndoAndRedoRestoreTheWholeTextAndSelection() {
        val original = NoteBlock.paragraph("前后")
        val before = EditorSnapshot(listOf(original), original.id, 2, selection = TextRange(2, 1))
        val after = before.copy(
            blocks = listOf(original.copy(text = "前中文")),
            cursorOffset = 3,
            selection = TextRange(3),
        )
        val history = EditorUndoStack()
        history.recordTypingChange(before, nowMs = 100, forceBreak = true, composing = true)
        assertEquals(UndoRedoOutcome.Applied(before), history.undo(after))
        assertFalse(history.canUndo)
        assertEquals(UndoRedoOutcome.Applied(after), history.redo(before))
    }

    @Test
    fun undoAndRedoTextKeepTheIndependentlyEditedTodoSchedule() {
        val todo = NoteBlock.checkbox("Original")
        val document = NoteDocument(listOf(todo))
        val draft = DraftEntity("draft", "test", "note", 1, document.encode(), 0, "2026-11-25T00:00:00Z")
        val session = EditorSession(draft, document, existingNote = true)
        val engine = EditorEngineState(session)
        val before = engine.snapshot(todo.id, false)
        engine.undoStack.recordTypingChange(before, nowMs = 100)
        engine.updateSingleBlock(0, todo.copy(text = "Edited"))

        session.blocks.single().apply {
            dueDate = "2026-12-01"
            reminderId = "saved-reminder"
            occurrenceId = "saved-occurrence"
            reminderStatus = ReminderStatus.ACTIVE
            reminderLocalTime = "09:30"
            recurrenceRule = RecurrenceRule.WEEKLY
            reminderPolicy = ReminderPolicy.KEEP_ORIGIN_ZONE
            markSyncedWith()
        }

        assertNull(engine.performUndo(todo.id, false))
        val undo = session.blocks.single()
        assertEquals("Original", undo.text.text.toString())
        assertEquals("2026-12-01", undo.documentBlock().dueDate)
        assertEquals("saved-reminder", undo.documentBlock().reminderId)
        assertEquals("saved-occurrence", undo.documentBlock().occurrenceId)
        assertEquals("2026-12-01", undo.syncedDueDate)
        assertEquals("saved-reminder", undo.syncedReminderId)
        assertEquals(ReminderStatus.ACTIVE, undo.reminderStatus)
        assertEquals("09:30", undo.reminderLocalTime)
        assertEquals(RecurrenceRule.WEEKLY, undo.recurrenceRule)
        assertEquals(ReminderPolicy.KEEP_ORIGIN_ZONE, undo.reminderPolicy)
        assertFalse(undo.pendingScheduleChanged)

        // Explicitly clearing the date before redoing text must not resurrect the old schedule.
        undo.dueDate = null
        undo.reminderId = null
        undo.occurrenceId = null
        undo.reminderStatus = null
        undo.reminderLocalTime = "10:15"
        undo.pendingReminderTime = "10:15"
        undo.pendingScheduleChanged = true

        assertNull(engine.performRedo(todo.id, false))
        val redo = session.blocks.single()
        assertEquals("Edited", redo.text.text.toString())
        assertNull(redo.documentBlock().dueDate)
        assertNull(redo.documentBlock().reminderId)
        assertNull(redo.documentBlock().occurrenceId)
        assertNull(redo.reminderStatus)
        assertEquals("10:15", redo.reminderLocalTime)
        assertEquals("10:15", redo.pendingReminderTime)
        assertEquals(true, redo.pendingScheduleChanged)
    }

    @Test
    fun enterReplacesAReversedSelectionAtTheStartOfAHeading() {
        val heading = NoteBlock.heading(level = 1, text = "开头正文")
        val newText = "\n正文"
        val split = EditorBlockOps.isSingleNewlineInsertion(heading.text, newText, TextRange(2, 0), TextRange(1))
        assertEquals(0, split)
        val outcome = EditorBlockOps.applyEnter(listOf(heading), 0, "", "正文")
        assertEquals(listOf("paragraph", "heading"), outcome.blocks.map { it.type })
        assertEquals("正文", outcome.blocks[1].text)
        assertEquals(heading.id, outcome.focusBlockId)
        assertNull(EditorBlockOps.isSingleNewlineInsertion("甲\n乙丙", "甲\n丙", TextRange(2), TextRange(2)))
    }

    @Test
    fun backspacePastAReferenceReturnsToTheVisibleToggleTitle() {
        val toggle = NoteBlock.toggle(title = "折叠标题", text = "隐藏正文", collapsed = true)
        val blocks = listOf(toggle, NoteBlock.reference("note", revision = 1, label = "引用"), NoteBlock.paragraph())
        val outcome = requireNotNull(EditorBlockOps.applyBackspaceAtStart(blocks, 2))
        assertEquals(blocks.dropLast(1), outcome.blocks)
        assertEquals(toggle.id, outcome.focusBlockId)
        assertEquals(true, outcome.focusToggleTitle)
        assertEquals(toggle.title.length, outcome.cursorOffset)
    }

    private data class Item(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
        override val contentType: Any? = null,
    ) : LazyListItemInfo
}
