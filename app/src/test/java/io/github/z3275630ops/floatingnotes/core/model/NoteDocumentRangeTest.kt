package io.github.z3275630ops.floatingnotes.core.model

import androidx.compose.ui.text.TextRange
import io.github.z3275630ops.floatingnotes.feature.capture.InlineTriggerKind
import io.github.z3275630ops.floatingnotes.feature.capture.detectInlineTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoteDocumentRangeTest {

    @Test
    fun clampUtf16RangeNeverSplitsSurrogatePairs() {
        val text = "A\uD83D\uDE00B" // "A😀B", emoji at [1, 3)
        assertEquals(0..3, clampUtf16Range(text, 0, 4))
        assertEquals(1..2, clampUtf16Range(text, 1, 3))

        // Starting inside the low surrogate snaps backward to the high surrogate.
        assertEquals(1..3, clampUtf16Range(text, 2, 4))
        assertEquals("😀B", safeUtf16Slice(text, 2, 4))

        // Ending inside the low surrogate snaps forward past the low surrogate.
        assertEquals(0..2, clampUtf16Range(text, 0, 2))
        assertEquals("A😀", safeUtf16Slice(text, 0, 2))

        // Same index inside a surrogate aligns safely to an empty range.
        val empty = clampUtf16Range(text, 2, 2)
        assertEquals(true, empty.isEmpty())
        assertEquals("", safeUtf16Slice(text, 2, 2))
    }

    @Test
    fun clampUtf16RangeHandlesEmptyAndReversedBounds() {
        assertEquals("", safeUtf16Slice("", 0, 5))
        assertEquals("中文", safeUtf16Slice("你好中文世界", 4, 2))
        assertEquals("世界", safeUtf16Slice("你好中文世界", 4, 99))
    }

    @Test
    fun detectInlineTriggerRespectsImeCompositionAndWordBoundaries() {
        // Active IME composition must suppress # and @ suggestions (Q060/Q095).
        assertNull(
            detectInlineTrigger(
                text = "今天学习 #待",
                selection = TextRange(7),
                composition = TextRange(6, 7),
            ),
        )

        // Email address mid-word '@' must not trigger.
        assertNull(
            detectInlineTrigger(
                text = "联系 user@example",
                selection = TextRange(15),
                composition = null,
            ),
        )

        // Valid '#' tag trigger at token boundary when composition == null.
        val tagTrigger = detectInlineTrigger(
            text = "整理 #工作",
            selection = TextRange(6),
            composition = null,
        )
        assertNotNull(tagTrigger)
        assertEquals(InlineTriggerKind.TAG, tagTrigger!!.kind)
        assertEquals(3, tagTrigger.triggerIndex)
        assertEquals(6, tagTrigger.cursorIndex)
        assertEquals("工作", tagTrigger.query)

        // Valid '@' note-link trigger at start of line.
        val refTrigger = detectInlineTrigger(
            text = "@架构",
            selection = TextRange(3),
            composition = null,
        )
        assertNotNull(refTrigger)
        assertEquals(InlineTriggerKind.REFERENCE, refTrigger!!.kind)
        assertEquals(0, refTrigger.triggerIndex)
        assertEquals("架构", refTrigger.query)
    }
}
