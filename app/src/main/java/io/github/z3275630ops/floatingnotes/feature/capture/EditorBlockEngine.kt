package io.github.z3275630ops.floatingnotes.feature.capture

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteMarkType
import io.github.z3275630ops.floatingnotes.core.model.NoteTextMark
import io.github.z3275630ops.floatingnotes.core.model.adjustMarksForTextEdit
import io.github.z3275630ops.floatingnotes.core.model.clampUtf16Range
import io.github.z3275630ops.floatingnotes.core.model.hasMarkAtSelection
import io.github.z3275630ops.floatingnotes.core.model.mergeMarks
import io.github.z3275630ops.floatingnotes.core.model.normalizeMarks
import io.github.z3275630ops.floatingnotes.core.model.splitMarksAt
import io.github.z3275630ops.floatingnotes.core.model.toggleTextMark
import io.github.z3275630ops.floatingnotes.data.discovery.LocalFuzzyMatcher
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import kotlinx.coroutines.awaitCancellation
import kotlin.math.abs

internal data class SlashTrigger(
    val triggerIndex: Int,
    val cursorIndex: Int,
    val query: String,
)

internal data class SlashCommand(
    val id: String,
    val label: String,
    val subtitle: String,
    val targetType: String,
    val headingLevel: Int = 1,
    val shortcutHint: String,
    val aliases: List<String>,
    val icon: ImageVector,
)

internal data class BlockEditOutcome(
    val blocks: List<NoteBlock>,
    val focusBlockId: String?,
    val cursorOffset: Int,
    val focusToggleTitle: Boolean = false,
    val statusHint: String? = null,
)

internal data class EditorSnapshot(
    val blocks: List<NoteBlock>,
    val focusedBlockId: String?,
    val cursorOffset: Int,
    val focusToggleTitle: Boolean = false,
)

internal sealed interface UndoRedoOutcome {
    data class Applied(val snapshot: EditorSnapshot) : UndoRedoOutcome
    data class AtBoundary(val message: String) : UndoRedoOutcome
}

internal object SlashCommandCatalog {
    val commands: List<SlashCommand> by lazy {
        listOf(
            SlashCommand(
                id = "paragraph",
                label = "正文",
                subtitle = "普通文字段落",
                targetType = "paragraph",
                shortcutHint = "正文",
                aliases = listOf("zw", "zhengwen", "paragraph", "text", "p"),
                icon = EditorIcons.TextFormat,
            ),
            SlashCommand(
                id = "h1",
                label = "一级标题",
                subtitle = "章节大标题",
                targetType = "heading",
                headingLevel = 1,
                shortcutHint = "# ",
                aliases = listOf("bt", "bt1", "yjbt", "h1", "biaoti", "heading1"),
                icon = EditorIcons.TextFormat,
            ),
            SlashCommand(
                id = "h2",
                label = "二级标题",
                subtitle = "小节中标题",
                targetType = "heading",
                headingLevel = 2,
                shortcutHint = "## ",
                aliases = listOf("bt", "bt2", "ejbt", "h2", "biaoti", "heading2"),
                icon = EditorIcons.TextFormat,
            ),
            SlashCommand(
                id = "h3",
                label = "三级标题",
                subtitle = "段落小标题",
                targetType = "heading",
                headingLevel = 3,
                shortcutHint = "### ",
                aliases = listOf("bt", "bt3", "sjbt", "h3", "biaoti", "heading3"),
                icon = EditorIcons.TextFormat,
            ),
            SlashCommand(
                id = "checkbox",
                label = "待办事项",
                subtitle = "可勾选与设提醒",
                targetType = "checkbox",
                shortcutHint = "[] ",
                aliases = listOf("db", "dbsx", "daiban", "todo", "task", "checkbox"),
                icon = MintIcons.Checklist,
            ),
            SlashCommand(
                id = "bullet",
                label = "无序列表",
                subtitle = "圆点条目列表",
                targetType = "bullet",
                shortcutHint = "- ",
                aliases = listOf("lb", "wxlb", "liebiao", "bullet", "ul"),
                icon = EditorIcons.BulletList,
            ),
            SlashCommand(
                id = "numbered",
                label = "有序列表",
                subtitle = "自动编号列表",
                targetType = "numbered",
                shortcutHint = "1. ",
                aliases = listOf("bh", "yxlb", "bianhao", "numbered", "ol"),
                icon = EditorIcons.NumberedList,
            ),
            SlashCommand(
                id = "quote",
                label = "引用",
                subtitle = "竖条引用摘录",
                targetType = "quote",
                shortcutHint = "> ",
                aliases = listOf("yy", "yinyong", "quote"),
                icon = EditorIcons.Quote,
            ),
            SlashCommand(
                id = "callout",
                label = "提示框",
                subtitle = "柔和底色高亮提示",
                targetType = "callout",
                shortcutHint = "提示",
                aliases = listOf("ts", "tsk", "tishi", "callout", "note"),
                icon = EditorIcons.Callout,
            ),
            SlashCommand(
                id = "toggle",
                label = "折叠块",
                subtitle = "可收起长段细节",
                targetType = "toggle",
                shortcutHint = "折叠",
                aliases = listOf("zd", "zdk", "zhedie", "toggle", "collapse"),
                icon = EditorIcons.Toggle,
            ),
            SlashCommand(
                id = "divider",
                label = "分割线",
                subtitle = "分隔内容段落",
                targetType = "divider",
                shortcutHint = "---",
                aliases = listOf("fg", "fgx", "fengexian", "divider", "hr"),
                icon = EditorIcons.DividerLine,
            ),
        )
    }

    fun filter(query: String): List<SlashCommand> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return commands
        return commands.filter { cmd ->
            cmd.label.contains(trimmed, ignoreCase = true) ||
                cmd.subtitle.contains(trimmed, ignoreCase = true) ||
                cmd.aliases.any { it.contains(trimmed, ignoreCase = true) } ||
                LocalFuzzyMatcher.matchesTagOrNoteQuery(trimmed, cmd.label)
        }
    }
}

internal object EditorBlockOps {

    fun computeListNumbers(blocks: List<NoteBlock>): Map<String, Int> {
        val result = HashMap<String, Int>()
        val counters = IntArray(NoteBlock.MAX_INDENT + 1)
        for (block in blocks) {
            if (block.type == "numbered") {
                val d = block.indent.coerceIn(0, NoteBlock.MAX_INDENT)
                counters[d] += 1
                for (i in (d + 1) until counters.size) counters[i] = 0
                result[block.id] = counters[d]
            } else {
                val resetFrom = if (block.type == "bullet" || block.type == "checkbox") {
                    block.indent.coerceIn(0, NoteBlock.MAX_INDENT)
                } else {
                    0
                }
                for (i in resetFrom until counters.size) counters[i] = 0
            }
        }
        return result
    }

    fun detectSlashCommand(
        text: String,
        selection: TextRange,
        composition: TextRange?,
    ): SlashTrigger? {
        if (composition != null || !selection.collapsed) return null
        val cursor = selection.start.coerceIn(0, text.length)
        var index = cursor - 1
        while (index >= 0) {
            val ch = text[index]
            if (ch.isWhitespace()) return null
            if (ch == '/') {
                if (index == 0 || text[index - 1].isWhitespace()) {
                    return SlashTrigger(
                        triggerIndex = index,
                        cursorIndex = cursor,
                        query = text.substring(index + 1, cursor),
                    )
                }
                return null
            }
            index--
        }
        return null
    }

    fun isSingleNewlineInsertion(
        oldText: String,
        newText: String,
        newSelection: TextRange,
    ): Int? {
        if (!newSelection.collapsed) return null
        if (newText.length > oldText.length + 1) return null
        val cursorAfter = newSelection.start.coerceIn(0, newText.length)
        if (cursorAfter <= 0 || newText[cursorAfter - 1] != '\n') return null
        val splitIndex = cursorAfter - 1
        if (newText.length == oldText.length + 1 &&
            newText.substring(0, splitIndex) == oldText.substring(0, splitIndex) &&
            newText.substring(cursorAfter) == oldText.substring(splitIndex)
        ) {
            return splitIndex
        }
        // Selection replacement with a single '\n'
        val prefixLen = commonPrefixLength(oldText, newText, maxPrefix = splitIndex)
        val suffixLen = commonSuffixLength(oldText, newText, minIndexInOld = prefixLen, minIndexInNew = cursorAfter)
        val removedInOld = oldText.length - prefixLen - suffixLen
        val insertedInNew = newText.length - prefixLen - suffixLen
        return if (removedInOld >= 0 && insertedInNew == 1 && prefixLen == splitIndex) splitIndex else null
    }

    fun tryApplyMarkdownPrefix(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        newText: String,
        cursorOffset: Int,
    ): BlockEditOutcome? {
        if (blockIndex !in blocks.indices) return null
        val block = blocks[blockIndex]
        if (!block.editable || block.type == "toggle") return null
        val safeCursor = cursorOffset.coerceIn(0, newText.length)

        // Divider shortcut: "---" or "--- "
        if (block.type == "paragraph" && (newText == "---" || newText == "--- ") && safeCursor == newText.length) {
            val updated = blocks.toMutableList()
            val divider = block.convertToType("divider")
            val nextParagraph = NoteBlock.paragraph()
            updated[blockIndex] = divider
            updated.add(blockIndex + 1, nextParagraph)
            return BlockEditOutcome(
                blocks = updated,
                focusBlockId = nextParagraph.id,
                cursorOffset = 0,
            )
        }

        if (block.type != "paragraph") return null

        val prefixMatch: Pair<Int, (String, List<NoteTextMark>) -> NoteBlock>? = when {
            newText.startsWith("### ") && safeCursor >= 4 -> 4 to { rest, marks ->
                NoteBlock.heading(level = 3, text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            newText.startsWith("## ") && safeCursor >= 3 -> 3 to { rest, marks ->
                NoteBlock.heading(level = 2, text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            newText.startsWith("# ") && safeCursor >= 2 -> 2 to { rest, marks ->
                NoteBlock.heading(level = 1, text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            (newText.startsWith("- ") || newText.startsWith("* ")) && safeCursor >= 2 -> 2 to { rest, marks ->
                NoteBlock.bullet(text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            newText.startsWith("1. ") && safeCursor >= 3 -> 3 to { rest, marks ->
                NoteBlock.numbered(text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            newText.startsWith("[] ") && safeCursor >= 3 -> 3 to { rest, marks ->
                NoteBlock.checkbox(text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            newText.startsWith("[ ] ") && safeCursor >= 4 -> 4 to { rest, marks ->
                NoteBlock.checkbox(text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            newText.startsWith("> ") && safeCursor >= 2 -> 2 to { rest, marks ->
                NoteBlock.quote(text = rest, indent = block.indent, marks = marks).copy(id = block.id)
            }
            else -> null
        }

        if (prefixMatch == null) return null
        val (prefixLen, factory) = prefixMatch
        val rest = newText.substring(prefixLen)
        val shiftedMarks = shiftMarksAfterPrefixRemoval(block.marks, prefixLen, rest)
        val converted = factory(rest, shiftedMarks)
        val updated = blocks.toMutableList()
        updated[blockIndex] = converted
        return BlockEditOutcome(
            blocks = updated,
            focusBlockId = converted.id,
            cursorOffset = (safeCursor - prefixLen).coerceIn(0, rest.length),
        )
    }

    fun applySlashCommand(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        trigger: SlashTrigger,
        command: SlashCommand,
    ): BlockEditOutcome {
        if (blockIndex !in blocks.indices) {
            return BlockEditOutcome(blocks, null, 0)
        }
        val block = blocks[blockIndex]
        val text = block.text
        val safeStart = trigger.triggerIndex.coerceIn(0, text.length)
        val safeEnd = trigger.cursorIndex.coerceIn(safeStart, text.length)
        val rawCleaned = text.removeRange(safeStart, safeEnd)
        val cleanedText = if (safeEnd == text.length) rawCleaned.trimEnd() else rawCleaned
        val cleanedMarks = adjustMarksForTextEdit(block.marks, text, cleanedText, cleanedText.length.coerceAtMost(safeStart))
        val trimmedRemaining = cleanedText.trim()
        val updated = blocks.toMutableList()

        if (command.targetType == "divider") {
            if (trimmedRemaining.isEmpty()) {
                val divider = block.convertToType("divider")
                val nextParagraph = NoteBlock.paragraph()
                updated[blockIndex] = divider
                updated.add(blockIndex + 1, nextParagraph)
                return BlockEditOutcome(
                    blocks = updated,
                    focusBlockId = nextParagraph.id,
                    cursorOffset = 0,
                )
            } else {
                updated[blockIndex] = block.copy(text = cleanedText, marks = cleanedMarks)
                val divider = NoteBlock.divider()
                val nextParagraph = NoteBlock.paragraph()
                updated.add(blockIndex + 1, divider)
                updated.add(blockIndex + 2, nextParagraph)
                return BlockEditOutcome(
                    blocks = updated,
                    focusBlockId = nextParagraph.id,
                    cursorOffset = 0,
                )
            }
        }

        val replaceInPlace = safeStart == 0 || trimmedRemaining.isEmpty()
        if (replaceInPlace) {
            val base = block.copy(text = if (trimmedRemaining.isEmpty()) "" else cleanedText, marks = cleanedMarks)
            val converted = base.convertToType(
                newType = command.targetType,
                headingLevel = command.headingLevel,
            )
            updated[blockIndex] = converted
            val nextCursor = if (converted.type == "toggle") {
                converted.title.length
            } else {
                safeStart.coerceIn(0, converted.text.length)
            }
            return BlockEditOutcome(
                blocks = updated,
                focusBlockId = converted.id,
                cursorOffset = nextCursor,
                focusToggleTitle = converted.type == "toggle",
            )
        } else {
            updated[blockIndex] = block.copy(text = cleanedText, marks = cleanedMarks)
            val inserted = when (command.targetType) {
                "heading" -> NoteBlock.heading(level = command.headingLevel)
                "checkbox" -> NoteBlock.checkbox()
                "bullet" -> NoteBlock.bullet()
                "numbered" -> NoteBlock.numbered()
                "quote" -> NoteBlock.quote()
                "callout" -> NoteBlock.callout()
                "toggle" -> NoteBlock.toggle()
                else -> NoteBlock.paragraph()
            }
            updated.add(blockIndex + 1, inserted)
            return BlockEditOutcome(
                blocks = updated,
                focusBlockId = inserted.id,
                cursorOffset = 0,
                focusToggleTitle = inserted.type == "toggle",
            )
        }
    }

    fun applyEnter(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        beforeCursor: String,
        afterCursor: String,
        isToggleTitle: Boolean = false,
    ): BlockEditOutcome {
        if (blockIndex !in blocks.indices) return BlockEditOutcome(blocks, null, 0)
        val block = blocks[blockIndex]
        val updated = blocks.toMutableList()

        if (block.type == "toggle" && isToggleTitle) {
            val updatedToggle = block.withToggleState(
                title = beforeCursor + afterCursor,
                collapsed = false,
            )
            updated[blockIndex] = updatedToggle
            return BlockEditOutcome(
                blocks = updated,
                focusBlockId = updatedToggle.id,
                cursorOffset = 0,
                focusToggleTitle = false,
            )
        }

        if (block.type == "callout" || block.type == "toggle") {
            if (block.type == "callout" && beforeCursor.isEmpty() && afterCursor.isEmpty()) {
                val degraded = block.convertToType("paragraph").copy(indent = 0)
                updated[blockIndex] = degraded
                return BlockEditOutcome(updated, degraded.id, 0)
            }
            if (afterCursor.isEmpty() && beforeCursor.endsWith("\n")) {
                val trimmedBody = beforeCursor.dropLast(1)
                val trimmedMarks = normalizeMarks(block.marks, trimmedBody)
                updated[blockIndex] = block.copy(text = trimmedBody, marks = trimmedMarks)
                val nextParagraph = NoteBlock.paragraph()
                updated.add(blockIndex + 1, nextParagraph)
                return BlockEditOutcome(updated, nextParagraph.id, 0)
            }
            val joined = "$beforeCursor\n$afterCursor"
            val newMarks = adjustMarksForTextEdit(
                marks = block.marks,
                oldText = beforeCursor + afterCursor,
                newText = joined,
                cursorAfterEdit = beforeCursor.length + 1,
            )
            updated[blockIndex] = block.copy(text = joined, marks = newMarks)
            return BlockEditOutcome(
                blocks = updated,
                focusBlockId = block.id,
                cursorOffset = beforeCursor.length + 1,
                focusToggleTitle = false,
            )
        }

        // Empty list / checkbox / quote / heading degrades to plain paragraph
        if (beforeCursor.isEmpty() && afterCursor.isEmpty()) {
            if (block.type in setOf("bullet", "numbered", "checkbox", "quote", "heading")) {
                val degraded = block.convertToType("paragraph").copy(indent = 0, marks = emptyList())
                updated[blockIndex] = degraded
                return BlockEditOutcome(updated, degraded.id, 0)
            }
            if (block.type == "paragraph" && block.indent > 0) {
                val unindented = block.copy(indent = 0)
                updated[blockIndex] = unindented
                return BlockEditOutcome(updated, unindented.id, 0)
            }
        }

        val (leftMarks, rightMarks) = splitMarksAt(block.marks, beforeCursor.length, beforeCursor, afterCursor)

        return when (block.type) {
            "heading" -> {
                if (beforeCursor.isEmpty() && afterCursor.isNotEmpty()) {
                    val insertedAbove = NoteBlock.paragraph()
                    updated.add(blockIndex, insertedAbove)
                    BlockEditOutcome(updated, block.id, 0)
                } else {
                    val left = block.copy(text = beforeCursor, marks = leftMarks)
                    val right = NoteBlock.paragraph(text = afterCursor, indent = 0, marks = rightMarks)
                    updated[blockIndex] = left
                    updated.add(blockIndex + 1, right)
                    BlockEditOutcome(updated, right.id, 0)
                }
            }
            "bullet", "numbered", "checkbox", "quote" -> {
                val left = block.copy(text = beforeCursor, marks = leftMarks)
                val right = when (block.type) {
                    "bullet" -> NoteBlock.bullet(text = afterCursor, indent = block.indent, marks = rightMarks)
                    "numbered" -> NoteBlock.numbered(text = afterCursor, indent = block.indent, marks = rightMarks)
                    "checkbox" -> NoteBlock.checkbox(text = afterCursor, checked = false, indent = block.indent, marks = rightMarks)
                    else -> NoteBlock.quote(text = afterCursor, indent = block.indent, marks = rightMarks)
                }
                updated[blockIndex] = left
                updated.add(blockIndex + 1, right)
                BlockEditOutcome(updated, right.id, 0)
            }
            else -> {
                val left = block.copy(text = beforeCursor, marks = leftMarks)
                val right = NoteBlock.paragraph(text = afterCursor, indent = block.indent, marks = rightMarks)
                updated[blockIndex] = left
                updated.add(blockIndex + 1, right)
                BlockEditOutcome(updated, right.id, 0)
            }
        }
    }

    fun applyBackspaceAtStart(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        isToggleTitle: Boolean = false,
    ): BlockEditOutcome? {
        if (blockIndex !in blocks.indices) return null
        val block = blocks[blockIndex]
        val updated = blocks.toMutableList()

        if (block.type == "toggle") {
            if (!isToggleTitle) {
                if (block.text.isEmpty() && block.title.isEmpty()) {
                    val degraded = block.convertToType("paragraph").copy(indent = 0)
                    updated[blockIndex] = degraded
                    return BlockEditOutcome(updated, degraded.id, 0)
                }
                // Move cursor back into toggle title when pressing Backspace at start of toggle body.
                return BlockEditOutcome(
                    blocks = updated,
                    focusBlockId = block.id,
                    cursorOffset = block.title.length,
                    focusToggleTitle = true,
                )
            } else {
                val degraded = block.convertToType("paragraph").copy(indent = 0)
                updated[blockIndex] = degraded
                return BlockEditOutcome(updated, degraded.id, 0)
            }
        }

        // Non-paragraph blocks degrade to paragraph first before merging upward
        if (block.type != "paragraph") {
            val degraded = block.convertToType("paragraph").copy(indent = 0)
            updated[blockIndex] = degraded
            return BlockEditOutcome(updated, degraded.id, 0)
        }

        if (block.indent > 0) {
            val unindented = block.copy(indent = block.indent - 1)
            updated[blockIndex] = unindented
            return BlockEditOutcome(updated, unindented.id, 0)
        }

        if (blockIndex == 0) return null
        val prev = blocks[blockIndex - 1]
        if (!prev.editable) {
            if (prev.type == "divider" && block.text.isEmpty()) {
                updated.removeAt(blockIndex - 1)
                return BlockEditOutcome(updated, block.id, 0)
            }
            if (block.text.isEmpty() && blocks.size > 1) {
                // Keep media/reference intact; remove redundant empty trailing paragraph only if safe.
                val prevEditableIdx = (blockIndex - 1 downTo 0).firstOrNull { updated[it].editable }
                if (prevEditableIdx != null) {
                    updated.removeAt(blockIndex)
                    val target = updated[prevEditableIdx]
                    return BlockEditOutcome(updated, target.id, target.text.length)
                }
            }
            return null
        }

        if (prev.type == "toggle") {
            val joinOffset = prev.text.length
            val mergedBody = prev.text + block.text
            val mergedMarks = mergeMarks(prev.marks, prev.text.length, block.marks, mergedBody)
            updated[blockIndex - 1] = prev.copy(text = mergedBody, marks = mergedMarks, collapsed = false)
            updated.removeAt(blockIndex)
            return BlockEditOutcome(updated, prev.id, joinOffset, focusToggleTitle = false)
        }

        val joinOffset = prev.text.length
        val mergedText = prev.text + block.text
        val mergedMarks = mergeMarks(prev.marks, prev.text.length, block.marks, mergedText)
        updated[blockIndex - 1] = prev.copy(text = mergedText, marks = mergedMarks)
        updated.removeAt(blockIndex)
        return BlockEditOutcome(updated, prev.id, joinOffset)
    }

    fun convertBlockType(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        targetType: String,
        headingLevel: Int = 1,
        calloutTone: String? = null,
        dividerStyle: String? = null,
    ): BlockEditOutcome {
        if (blockIndex !in blocks.indices) return BlockEditOutcome(blocks, null, 0)
        val block = blocks[blockIndex]
        val updated = blocks.toMutableList()
        if (targetType == "divider") {
            val div = block.convertToType(
                newType = "divider",
                dividerStyle = dividerStyle ?: block.dividerStyle,
            )
            updated[blockIndex] = div
            val next = updated.getOrNull(blockIndex + 1)
            val focusTarget = if (next != null && next.editable) {
                next
            } else {
                NoteBlock.paragraph().also { updated.add(blockIndex + 1, it) }
            }
            return BlockEditOutcome(updated, focusTarget.id, 0)
        }
        val converted = block.convertToType(
            newType = targetType,
            headingLevel = headingLevel,
            calloutTone = calloutTone ?: block.tone,
            dividerStyle = dividerStyle ?: block.dividerStyle,
        )
        updated[blockIndex] = converted
        return BlockEditOutcome(
            blocks = updated,
            focusBlockId = converted.id,
            cursorOffset = if (converted.type == "toggle") converted.title.length else converted.text.length,
            focusToggleTitle = converted.type == "toggle",
        )
    }

    fun adjustIndent(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        delta: Int,
    ): BlockEditOutcome {
        if (blockIndex !in blocks.indices) return BlockEditOutcome(blocks, null, 0)
        val block = blocks[blockIndex]
        if (!block.editable) return BlockEditOutcome(blocks, block.id, 0)
        val nextIndent = (block.indent + delta).coerceIn(0, NoteBlock.MAX_INDENT)
        if (nextIndent == block.indent) {
            return BlockEditOutcome(
                blocks = blocks,
                focusBlockId = block.id,
                cursorOffset = block.text.length,
                statusHint = if (delta > 0) "已达最大缩进层级" else "已是顶格对齐",
            )
        }
        val updated = blocks.toMutableList()
        updated[blockIndex] = block.withIndent(nextIndent)
        return BlockEditOutcome(updated, block.id, block.text.length)
    }

    fun moveBlock(
        blocks: List<NoteBlock>,
        fromIndex: Int,
        toIndex: Int,
    ): List<NoteBlock> {
        if (fromIndex !in blocks.indices || toIndex !in blocks.indices || fromIndex == toIndex) {
            return blocks
        }
        val updated = blocks.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)
        return updated
    }

    fun deleteBlock(
        blocks: List<NoteBlock>,
        blockIndex: Int,
    ): BlockEditOutcome {
        if (blockIndex !in blocks.indices) return BlockEditOutcome(blocks, null, 0)
        val updated = blocks.toMutableList()
        updated.removeAt(blockIndex)
        if (updated.isEmpty()) {
            val fresh = NoteBlock.paragraph()
            updated.add(fresh)
            return BlockEditOutcome(updated, fresh.id, 0)
        }
        val focusIdx = (blockIndex - 1).coerceAtLeast(0).coerceAtMost(updated.lastIndex)
        val target = updated[focusIdx]
        return BlockEditOutcome(
            blocks = updated,
            focusBlockId = if (target.editable) target.id else null,
            cursorOffset = if (target.editable) target.text.length else 0,
        )
    }

    fun toggleMarkOnRange(
        blocks: List<NoteBlock>,
        blockIndex: Int,
        markType: NoteMarkType,
        start: Int,
        end: Int,
    ): BlockEditOutcome {
        if (blockIndex !in blocks.indices) return BlockEditOutcome(blocks, null, 0)
        val block = blocks[blockIndex]
        if (!block.editable) return BlockEditOutcome(blocks, block.id, 0)
        val updatedMarks = toggleTextMark(block.marks, markType, start, end, block.text)
        val updated = blocks.toMutableList()
        updated[blockIndex] = block.withMarks(updatedMarks)
        return BlockEditOutcome(updated, block.id, end.coerceIn(0, block.text.length))
    }

    fun tapBlankCanvas(blocks: List<NoteBlock>): BlockEditOutcome {
        val tail = blocks.lastOrNull()
        if (tail != null &&
            tail.type == "paragraph" &&
            tail.text.isEmpty() &&
            tail.marks.isEmpty()
        ) {
            return BlockEditOutcome(
                blocks = blocks,
                focusBlockId = tail.id,
                cursorOffset = 0,
            )
        }
        val added = NoteBlock.paragraph()
        return BlockEditOutcome(
            blocks = blocks + added,
            focusBlockId = added.id,
            cursorOffset = 0,
        )
    }

    fun toggleBlockSelection(selectedIds: Set<String>, blockId: String): Set<String> =
        if (blockId in selectedIds) selectedIds - blockId else selectedIds + blockId

    fun selectAllBlocks(blocks: List<NoteBlock>): Set<String> =
        blocks.mapTo(LinkedHashSet()) { it.id }

    fun batchToggleTodo(
        blocks: List<NoteBlock>,
        selectedIds: Set<String>,
    ): BlockEditOutcome {
        val selectedEditable = blocks.filter { it.id in selectedIds && it.editable }
        if (selectedEditable.isEmpty()) return BlockEditOutcome(blocks, null, 0)
        val allCheckboxes = selectedEditable.all { it.type == "checkbox" }
        val targetType = if (allCheckboxes) "paragraph" else "checkbox"
        return batchConvertType(blocks, selectedIds, targetType)
    }

    fun batchConvertType(
        blocks: List<NoteBlock>,
        selectedIds: Set<String>,
        targetType: String,
        headingLevel: Int = 1,
    ): BlockEditOutcome {
        if (selectedIds.isEmpty()) return BlockEditOutcome(blocks, null, 0)
        val updated = blocks.map { block ->
            if (block.id in selectedIds && (block.editable || (block.type == "divider" && targetType != "divider"))) {
                block.convertToType(newType = targetType, headingLevel = headingLevel)
            } else {
                block
            }
        }.toMutableList()
        if (updated.lastOrNull()?.type == "divider") {
            updated.add(NoteBlock.paragraph())
        }
        return BlockEditOutcome(updated, null, 0)
    }

    fun batchDeleteBlocks(
        blocks: List<NoteBlock>,
        selectedIds: Set<String>,
    ): BlockEditOutcome {
        if (selectedIds.isEmpty()) return BlockEditOutcome(blocks, null, 0)
        val remaining = blocks.filterNot { it.id in selectedIds }.toMutableList()
        if (remaining.isEmpty()) {
            remaining.add(NoteBlock.paragraph())
        }
        return BlockEditOutcome(remaining, null, 0)
    }

    fun batchCopyText(
        blocks: List<NoteBlock>,
        selectedIds: Set<String>,
    ): String = blocks
        .filter { it.id in selectedIds }
        .mapNotNull { block ->
            when {
                block.type == "divider" -> "---"
                block.searchableText.isNotBlank() -> block.searchableText
                else -> null
            }
        }
        .joinToString("\n")

    private fun shiftMarksAfterPrefixRemoval(
        marks: List<NoteTextMark>,
        prefixLen: Int,
        rest: String,
    ): List<NoteTextMark> {
        if (marks.isEmpty() || rest.isEmpty()) return emptyList()
        val shifted = marks.mapNotNull { mark ->
            val s = (mark.start - prefixLen).coerceAtLeast(0)
            val e = (mark.end - prefixLen).coerceAtMost(rest.length)
            if (s < e) NoteTextMark(mark.type, s, e) else null
        }
        return normalizeMarks(shifted, rest)
    }

    private fun commonPrefixLength(a: String, b: String, maxPrefix: Int): Int {
        val limit = minOf(a.length, b.length, maxPrefix)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffixLength(a: String, b: String, minIndexInOld: Int, minIndexInNew: Int): Int {
        var s = 0
        while (s < a.length - minIndexInOld && s < b.length - minIndexInNew) {
            if (a[a.length - 1 - s] != b[b.length - 1 - s]) break
            s++
        }
        return s
    }
}

internal class EditorUndoStack(
    private val maxDepth: Int = MAX_UNDO_DEPTH,
    private val coalesceWindowMs: Long = COALESCE_WINDOW_MS,
) {
    private val undoEntries = ArrayDeque<EditorSnapshot>()
    private val redoEntries = ArrayDeque<EditorSnapshot>()
    private var lastTypingAtMs = 0L
    private var lastTypingBlockId: String? = null

    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()

    fun clear() {
        undoEntries.clear()
        redoEntries.clear()
        lastTypingAtMs = 0L
        lastTypingBlockId = null
    }

    fun recordTypingChange(
        before: EditorSnapshot,
        nowMs: Long = System.currentTimeMillis(),
        forceBreak: Boolean = false,
    ) {
        val sameBlock = before.focusedBlockId != null && before.focusedBlockId == lastTypingBlockId
        val withinWindow = !forceBreak && sameBlock && (nowMs - lastTypingAtMs) <= coalesceWindowMs && undoEntries.isNotEmpty()
        if (!withinWindow) {
            pushUndo(before)
        }
        redoEntries.clear()
        lastTypingAtMs = nowMs
        lastTypingBlockId = before.focusedBlockId
    }

    fun recordStructuralChange(before: EditorSnapshot) {
        if (undoEntries.lastOrNull()?.blocks != before.blocks) {
            pushUndo(before)
        }
        redoEntries.clear()
        lastTypingAtMs = 0L
        lastTypingBlockId = null
    }

    fun undo(current: EditorSnapshot): UndoRedoOutcome {
        if (undoEntries.isEmpty()) {
            return UndoRedoOutcome.AtBoundary("已撤销到最初状态")
        }
        val target = undoEntries.removeLast()
        redoEntries.addLast(current)
        if (redoEntries.size > maxDepth) redoEntries.removeFirst()
        lastTypingAtMs = 0L
        lastTypingBlockId = null
        return UndoRedoOutcome.Applied(target)
    }

    fun redo(current: EditorSnapshot): UndoRedoOutcome {
        if (redoEntries.isEmpty()) {
            return UndoRedoOutcome.AtBoundary("没有可重做的操作")
        }
        val target = redoEntries.removeLast()
        pushUndo(current)
        lastTypingAtMs = 0L
        lastTypingBlockId = null
        return UndoRedoOutcome.Applied(target)
    }

    private fun pushUndo(snapshot: EditorSnapshot) {
        if (undoEntries.lastOrNull()?.blocks == snapshot.blocks) return
        undoEntries.addLast(snapshot)
        if (undoEntries.size > maxDepth) {
            undoEntries.removeFirst()
        }
    }

    companion object {
        private const val MAX_UNDO_DEPTH = 60
        private const val COALESCE_WINDOW_MS = 650L
    }
}

internal data class PendingBlockFocus(
    val blockId: String,
    val cursorOffset: Int,
    val toggleTitle: Boolean,
    val token: Int,
)

@Stable
internal class BlockDragController {
    var draggingBlockId by mutableStateOf<String?>(null)
    var draggingIndex by mutableIntStateOf(-1)
    var targetDropIndex by mutableIntStateOf(-1)
    var dragOffsetY by mutableFloatStateOf(0f)
    var draggingItemHeightPx by mutableIntStateOf(0)
    var pointerViewportY by mutableFloatStateOf(Float.NaN)
    var listTopInRootPx by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = draggingBlockId != null

    fun start(
        blockId: String,
        index: Int,
        itemHeightPx: Int,
        pointerRootY: Float,
    ) {
        draggingBlockId = blockId
        draggingIndex = index
        targetDropIndex = index
        dragOffsetY = 0f
        draggingItemHeightPx = itemHeightPx.coerceAtLeast(1)
        pointerViewportY = pointerRootY - listTopInRootPx
    }

    fun updateDrag(
        deltaY: Float,
        pointerRootY: Float,
        listState: LazyListState,
        blockCount: Int,
    ) {
        if (!isDragging) return
        dragOffsetY += deltaY
        if (!pointerRootY.isNaN()) {
            pointerViewportY = pointerRootY - listTopInRootPx
        }
        recomputeDropIndex(listState, blockCount)
    }

    fun recomputeDropIndex(listState: LazyListState, blockCount: Int) {
        if (!isDragging || blockCount <= 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
            .filter { it.index in 0 until blockCount }
        if (visible.isEmpty()) return
        val refY = if (!pointerViewportY.isNaN()) {
            pointerViewportY
        } else {
            val draggedInfo = visible.firstOrNull { it.index == draggingIndex } ?: return
            draggedInfo.offset + draggedInfo.size / 2f + dragOffsetY
        }
        var chosen = draggingIndex
        var bestDist = Float.MAX_VALUE
        for (item in visible) {
            val center = item.offset + item.size / 2f
            val dist = abs(refY - center)
            if (dist < bestDist) {
                bestDist = dist
                chosen = item.index
            }
        }
        targetDropIndex = chosen.coerceIn(0, blockCount - 1)
    }

    fun finish(): Pair<Int, Int>? {
        val from = draggingIndex
        val to = targetDropIndex
        cancel()
        return if (from >= 0 && to >= 0 && from != to) from to to else null
    }

    fun cancel() {
        draggingBlockId = null
        draggingIndex = -1
        targetDropIndex = -1
        dragOffsetY = 0f
        draggingItemHeightPx = 0
        pointerViewportY = Float.NaN
    }
}

@Stable
internal class EditorEngineState(private val session: EditorSession) {
    val undoStack = EditorUndoStack()
    val dragController = BlockDragController()
    val tfvMap = mutableMapOf<String, MutableState<TextFieldValue>>()
    val titleTfvMap = mutableMapOf<String, MutableState<TextFieldValue>>()
    val attachedFocusRequesters = mutableSetOf<String>()
    val attachedTitleFocusRequesters = mutableSetOf<String>()
    var pendingTypingMarks by mutableStateOf<Set<NoteMarkType>>(emptySet())
    var pendingFocus by mutableStateOf<PendingBlockFocus?>(null)
    var transientHint by mutableStateOf<String?>(null)
    var dismissedSlashAt by mutableStateOf<Pair<String, Int>?>(null)
    private var focusSeq = 0

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun refreshUndoFlags() {
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    fun indexOfBlock(blockId: String): Int = session.blocks.indexOfFirst { it.id == blockId }

    fun tfvStateFor(block: EditorBlock): MutableState<TextFieldValue> {
        val existing = tfvMap[block.id]
        val blockText = block.text.text.toString()
        if (existing != null) {
            if (existing.value.text != blockText) {
                val safeSel = clampSelection(existing.value.selection, blockText.length)
                existing.value = TextFieldValue(blockText, safeSel)
            }
            return existing
        }
        val initial = TextFieldValue(blockText, clampSelection(block.text.selection, blockText.length))
        return mutableStateOf(initial).also { tfvMap[block.id] = it }
    }

    fun titleTfvStateFor(block: EditorBlock): MutableState<TextFieldValue> {
        val docTitle = block.documentBlock().title
        val existing = titleTfvMap[block.id]
        if (existing != null) {
            if (existing.value.text != docTitle) {
                existing.value = TextFieldValue(docTitle, clampSelection(existing.value.selection, docTitle.length))
            }
            return existing
        }
        return mutableStateOf(TextFieldValue(docTitle, TextRange(docTitle.length))).also {
            titleTfvMap[block.id] = it
        }
    }

    fun snapshot(focusedBlockId: String?, focusToggleTitle: Boolean): EditorSnapshot {
        val blocks = session.blocks.map { it.documentBlock() }
        val activeTfv = focusedBlockId?.let { id ->
            if (focusToggleTitle) titleTfvMap[id]?.value else tfvMap[id]?.value
        }
        val cursor = activeTfv?.selection?.start ?: 0
        return EditorSnapshot(
            blocks = blocks,
            focusedBlockId = focusedBlockId,
            cursorOffset = cursor,
            focusToggleTitle = focusToggleTitle,
        )
    }

    fun requestFocus(
        blockId: String,
        cursorOffset: Int,
        toggleTitle: Boolean = false,
    ) {
        focusSeq += 1
        pendingFocus = PendingBlockFocus(
            blockId = blockId,
            cursorOffset = cursorOffset,
            toggleTitle = toggleTitle,
            token = focusSeq,
        )
    }

    fun applyOutcome(
        outcome: BlockEditOutcome,
        recordUndoBefore: EditorSnapshot? = null,
    ) {
        if (recordUndoBefore != null && outcome.blocks != recordUndoBefore.blocks) {
            undoStack.recordStructuralChange(recordUndoBefore)
        }
        replaceSessionBlocks(outcome.blocks)
        val focusId = outcome.focusBlockId
        if (focusId != null) {
            val targetDoc = outcome.blocks.firstOrNull { it.id == focusId }
            if (targetDoc != null) {
                if (outcome.focusToggleTitle && targetDoc.type == "toggle") {
                    val safeOff = outcome.cursorOffset.coerceIn(0, targetDoc.title.length)
                    titleTfvMap.getOrPut(focusId) { mutableStateOf(TextFieldValue(targetDoc.title)) }.value =
                        TextFieldValue(targetDoc.title, TextRange(safeOff))
                } else {
                    val safeOff = outcome.cursorOffset.coerceIn(0, targetDoc.text.length)
                    tfvMap.getOrPut(focusId) { mutableStateOf(TextFieldValue(targetDoc.text)) }.value =
                        TextFieldValue(targetDoc.text, TextRange(safeOff))
                }
                requestFocus(focusId, outcome.cursorOffset, outcome.focusToggleTitle)
            }
        }
        if (outcome.statusHint != null) {
            transientHint = outcome.statusHint
        }
        refreshUndoFlags()
    }

    fun performUndo(focusedBlockId: String?, focusToggleTitle: Boolean): String? {
        val current = snapshot(focusedBlockId, focusToggleTitle)
        return when (val result = undoStack.undo(current)) {
            is UndoRedoOutcome.AtBoundary -> {
                refreshUndoFlags()
                transientHint = result.message
                result.message
            }
            is UndoRedoOutcome.Applied -> {
                restoreSnapshot(result.snapshot)
                refreshUndoFlags()
                null
            }
        }
    }

    fun performRedo(focusedBlockId: String?, focusToggleTitle: Boolean): String? {
        val current = snapshot(focusedBlockId, focusToggleTitle)
        return when (val result = undoStack.redo(current)) {
            is UndoRedoOutcome.AtBoundary -> {
                refreshUndoFlags()
                transientHint = result.message
                result.message
            }
            is UndoRedoOutcome.Applied -> {
                restoreSnapshot(result.snapshot)
                refreshUndoFlags()
                null
            }
        }
    }

    private fun restoreSnapshot(snap: EditorSnapshot) {
        replaceSessionBlocks(snap.blocks)
        val focusId = snap.focusedBlockId
        if (focusId != null) {
            val targetDoc = snap.blocks.firstOrNull { it.id == focusId }
            if (targetDoc != null && targetDoc.editable) {
                if (snap.focusToggleTitle && targetDoc.type == "toggle") {
                    val safeOff = snap.cursorOffset.coerceIn(0, targetDoc.title.length)
                    titleTfvMap.getOrPut(focusId) { mutableStateOf(TextFieldValue(targetDoc.title)) }.value =
                        TextFieldValue(targetDoc.title, TextRange(safeOff))
                } else {
                    val safeOff = snap.cursorOffset.coerceIn(0, targetDoc.text.length)
                    tfvMap.getOrPut(focusId) { mutableStateOf(TextFieldValue(targetDoc.text)) }.value =
                        TextFieldValue(targetDoc.text, TextRange(safeOff))
                }
                requestFocus(focusId, snap.cursorOffset, snap.focusToggleTitle)
            }
        }
    }

    fun replaceSessionBlocks(targetBlocks: List<NoteBlock>) {
        val oldById = session.blocks.associateBy { it.id }
        val nextEditorBlocks = targetBlocks.map { docBlock ->
            val old = oldById[docBlock.id]
            if (old != null && old.documentBlock() == docBlock) {
                old
            } else if (old != null) {
                old.withUpdatedDocumentBlock(docBlock)
            } else {
                EditorBlock(docBlock)
            }
        }
        session.blocks.clear()
        session.blocks.addAll(nextEditorBlocks)

        val liveIds = targetBlocks.mapTo(HashSet()) { it.id }
        tfvMap.keys.retainAll(liveIds)
        titleTfvMap.keys.retainAll(liveIds)
        for (docBlock in targetBlocks) {
            val tfvState = tfvMap[docBlock.id]
            if (tfvState != null && tfvState.value.text != docBlock.text) {
                tfvState.value = TextFieldValue(
                    docBlock.text,
                    clampSelection(tfvState.value.selection, docBlock.text.length),
                )
            }
            if (docBlock.type == "toggle") {
                val titleState = titleTfvMap[docBlock.id]
                if (titleState != null && titleState.value.text != docBlock.title) {
                    titleState.value = TextFieldValue(
                        docBlock.title,
                        clampSelection(titleState.value.selection, docBlock.title.length),
                    )
                }
            }
        }
    }

    fun updateSingleBlock(blockIndex: Int, updatedDocBlock: NoteBlock) {
        if (blockIndex !in session.blocks.indices) return
        val old = session.blocks[blockIndex]
        if (old.documentBlock() == updatedDocBlock) return
        session.blocks[blockIndex] = old.withUpdatedDocumentBlock(updatedDocBlock)
    }

    fun activeMarksFor(block: EditorBlock, selection: TextRange): Set<NoteMarkType> {
        val doc = block.documentBlock()
        if (!doc.editable) return emptySet()
        if (selection.collapsed) {
            val atCursor = NoteMarkType.entries.filterTo(HashSet()) { type ->
                hasMarkAtSelection(doc.marks, type, selection.start, selection.end, doc.text)
            }
            return atCursor + pendingTypingMarks
        }
        return NoteMarkType.entries.filterTo(HashSet()) { type ->
            hasMarkAtSelection(doc.marks, type, selection.start, selection.end, doc.text)
        }
    }

    fun toggleMarkAction(
        blockIndex: Int,
        markType: NoteMarkType,
        selection: TextRange,
    ) {
        if (blockIndex !in session.blocks.indices) return
        val block = session.blocks[blockIndex]
        if (!block.editable) return
        if (selection.collapsed) {
            pendingTypingMarks = if (markType in pendingTypingMarks) {
                pendingTypingMarks - markType
            } else {
                pendingTypingMarks + markType
            }
            return
        }
        val before = snapshot(block.id, focusToggleTitle = false)
        val doc = block.documentBlock()
        val nextMarks = toggleTextMark(doc.marks, markType, selection.start, selection.end, doc.text)
        undoStack.recordStructuralChange(before)
        updateSingleBlock(blockIndex, doc.withMarks(nextMarks))
        refreshUndoFlags()
    }
}

@Stable
internal class EditorUndoHandle {
    var canUndo by mutableStateOf(false)
        internal set
    var canRedo by mutableStateOf(false)
        internal set
    private var undoAction: (() -> Unit)? = null
    private var redoAction: (() -> Unit)? = null

    fun performUndo() {
        undoAction?.invoke()
    }

    fun performRedo() {
        redoAction?.invoke()
    }

    internal fun bind(
        canUndo: Boolean,
        canRedo: Boolean,
        onUndo: () -> Unit,
        onRedo: () -> Unit,
    ) {
        this.canUndo = canUndo
        this.canRedo = canRedo
        this.undoAction = onUndo
        this.redoAction = onRedo
    }

    internal fun unbind() {
        canUndo = false
        canRedo = false
        undoAction = null
        redoAction = null
    }
}

@Composable
internal fun rememberEditorUndoHandle(): EditorUndoHandle = remember { EditorUndoHandle() }

@Composable
internal fun rememberEditorEngineState(session: EditorSession): EditorEngineState {
    return remember(session.draft.id) { EditorEngineState(session) }
}

internal fun EditorBlock.withUpdatedDocumentBlock(newBlock: NoteBlock): EditorBlock {
    val preserveCheckboxMeta = type == "checkbox" && newBlock.type == "checkbox"
    return EditorBlock(newBlock).apply {
        checked = newBlock.checked
        if (preserveCheckboxMeta) {
            dueDate = newBlock.dueDate ?: this@withUpdatedDocumentBlock.dueDate
            reminderId = newBlock.reminderId ?: this@withUpdatedDocumentBlock.reminderId
            occurrenceId = newBlock.occurrenceId ?: this@withUpdatedDocumentBlock.occurrenceId
            reminderLocalTime = this@withUpdatedDocumentBlock.reminderLocalTime
            recurrenceRule = this@withUpdatedDocumentBlock.recurrenceRule
            reminderPolicy = this@withUpdatedDocumentBlock.reminderPolicy
            reminderStatus = this@withUpdatedDocumentBlock.reminderStatus
            pendingReminderTime = this@withUpdatedDocumentBlock.pendingReminderTime
            pendingScheduleChanged = this@withUpdatedDocumentBlock.pendingScheduleChanged
        }
        syncedText = this@withUpdatedDocumentBlock.syncedText
        syncedChecked = this@withUpdatedDocumentBlock.syncedChecked
        syncedDueDate = if (preserveCheckboxMeta) this@withUpdatedDocumentBlock.syncedDueDate else null
        syncedReminderId = if (preserveCheckboxMeta) this@withUpdatedDocumentBlock.syncedReminderId else null
        syncedOccurrenceId = if (preserveCheckboxMeta) this@withUpdatedDocumentBlock.syncedOccurrenceId else null
    }
}

internal fun clampSelection(selection: TextRange, length: Int): TextRange =
    TextRange(selection.start.coerceIn(0, length), selection.end.coerceIn(0, length))

internal fun marksVisualTransformation(
    marks: List<NoteTextMark>,
    highlightBg: Color,
    highlightFg: Color,
): VisualTransformation {
    if (marks.isEmpty()) return VisualTransformation.None
    return VisualTransformation { original ->
        val raw = original.text
        if (raw.isEmpty()) {
            TransformedText(original, OffsetMapping.Identity)
        } else {
            val builder = AnnotatedString.Builder(raw)
            for (mark in marks) {
                val clamped = clampUtf16Range(raw, mark.start, mark.end)
                if (clamped.isEmpty()) continue
                val start = clamped.first
                val end = clamped.last + 1
                val style = when (mark.type) {
                    NoteMarkType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    NoteMarkType.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    NoteMarkType.HIGHLIGHT -> SpanStyle(background = highlightBg, color = highlightFg)
                }
                builder.addStyle(style, start, end)
            }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun backspaceAtStartInterceptor(
    shouldTriggerBackspaceAtStart: () -> Boolean,
    onBackspaceAtStart: () -> Boolean,
): PlatformTextInputInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    val wrappedRequest = androidx.compose.ui.platform.PlatformTextInputMethodRequest { outAttrs: EditorInfo ->
        val base = request.createInputConnection(outAttrs)
        BackspaceAtStartInputConnection(
            target = base,
            shouldTrigger = shouldTriggerBackspaceAtStart,
            onBackspaceAtStart = onBackspaceAtStart,
        )
    }
    nextHandler.startInputMethod(wrappedRequest)
    awaitCancellation()
}

private class BackspaceAtStartInputConnection(
    target: InputConnection,
    private val shouldTrigger: () -> Boolean,
    private val onBackspaceAtStart: () -> Boolean,
) : InputConnectionWrapper(target, true) {
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0 && shouldTrigger()) {
            if (onBackspaceAtStart()) return true
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0 && shouldTrigger()) {
            if (onBackspaceAtStart()) return true
        }
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DEL &&
            shouldTrigger() &&
            onBackspaceAtStart()
        ) {
            return true
        }
        return super.sendKeyEvent(event)
    }
}

internal suspend fun autoScrollDuringBlockDrag(
    dragController: BlockDragController,
    listState: LazyListState,
    blockCount: Int,
    edgeThresholdPx: Float,
    maxStepPx: Float,
) {
    while (dragController.isDragging) {
        val y = dragController.pointerViewportY
        val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        if (!y.isNaN() && viewportHeight > 0) {
            val delta = when {
                y < edgeThresholdPx -> {
                    val ratio = ((edgeThresholdPx - y) / edgeThresholdPx).coerceIn(0.15f, 1f)
                    -maxStepPx * ratio
                }
                y > viewportHeight - edgeThresholdPx -> {
                    val ratio = ((y - (viewportHeight - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0.15f, 1f)
                    maxStepPx * ratio
                }
                else -> 0f
            }
            if (delta != 0f) {
                val scrolled = listState.scrollBy(delta)
                if (scrolled != 0f) {
                    dragController.recomputeDropIndex(listState, blockCount)
                }
            }
        }
        kotlinx.coroutines.delay(16L)
    }
}

internal object EditorIcons {
    val Undo: ImageVector by lazy {
        strokeIcon("EditorUndo") {
            moveTo(9f, 7f); lineTo(4.5f, 11.5f); lineTo(9f, 16f)
            moveTo(5f, 11.5f); horizontalLineTo(14.5f)
            curveTo(17.5f, 11.5f, 19.5f, 13.5f, 19.5f, 16f)
            curveTo(19.5f, 18.2f, 17.7f, 19.5f, 15.2f, 19.5f)
            horizontalLineTo(12.5f)
        }
    }

    val Redo: ImageVector by lazy {
        strokeIcon("EditorRedo") {
            moveTo(15f, 7f); lineTo(19.5f, 11.5f); lineTo(15f, 16f)
            moveTo(19f, 11.5f); horizontalLineTo(9.5f)
            curveTo(6.5f, 11.5f, 4.5f, 13.5f, 4.5f, 16f)
            curveTo(4.5f, 18.2f, 6.3f, 19.5f, 8.8f, 19.5f)
            horizontalLineTo(11.5f)
        }
    }

    val KeyboardHide: ImageVector by lazy {
        strokeIcon("EditorKeyboardHide") {
            roundedRect(3.5f, 4.5f, 20.5f, 14f, 2f)
            moveTo(7f, 8f); horizontalLineTo(8.5f)
            moveTo(11.2f, 8f); horizontalLineTo(12.8f)
            moveTo(15.5f, 8f); horizontalLineTo(17f)
            moveTo(8f, 11f); horizontalLineTo(16f)
            moveTo(9f, 17f); lineTo(12f, 20f); lineTo(15f, 17f)
        }
    }

    val DragHandle: ImageVector by lazy {
        fillIcon("EditorDragHandle") {
            circle(9f, 6.5f, 1.4f)
            circle(15f, 6.5f, 1.4f)
            circle(9f, 12f, 1.4f)
            circle(15f, 12f, 1.4f)
            circle(9f, 17.5f, 1.4f)
            circle(15f, 17.5f, 1.4f)
        }
    }

    val TextFormat: ImageVector by lazy {
        strokeIcon("EditorTextFormat") {
            moveTo(4.5f, 17.5f); lineTo(8.5f, 6.5f); lineTo(12.5f, 17.5f)
            moveTo(5.8f, 14f); horizontalLineTo(11.2f)
            moveTo(14.5f, 12.2f); curveTo(14.5f, 10.8f, 15.8f, 10f, 17.3f, 10f)
            curveTo(18.8f, 10f, 19.5f, 10.9f, 19.5f, 12.3f); verticalLineTo(17.5f)
            moveTo(19.5f, 13.8f); horizontalLineTo(16.8f)
            curveTo(15.2f, 13.8f, 14.3f, 14.6f, 14.3f, 15.7f)
            curveTo(14.3f, 16.8f, 15.2f, 17.5f, 16.7f, 17.5f)
            curveTo(18.1f, 17.5f, 19.1f, 16.8f, 19.5f, 15.8f)
        }
    }

    val Bold: ImageVector by lazy {
        strokeIcon("EditorBold", strokeWidth = 2.2f) {
            moveTo(7.5f, 5.5f); verticalLineTo(18.5f)
            moveTo(7.5f, 5.5f); horizontalLineTo(13f)
            curveTo(15f, 5.5f, 16.5f, 6.8f, 16.5f, 8.6f)
            curveTo(16.5f, 10.4f, 15f, 11.7f, 13f, 11.7f); horizontalLineTo(7.5f)
            moveTo(7.5f, 11.7f); horizontalLineTo(13.8f)
            curveTo(16f, 11.7f, 17.5f, 13.1f, 17.5f, 15.1f)
            curveTo(17.5f, 17.1f, 16f, 18.5f, 13.8f, 18.5f); horizontalLineTo(7.5f)
        }
    }

    val Highlight: ImageVector by lazy {
        strokeIcon("EditorHighlight") {
            moveTo(14.8f, 4.5f); lineTo(19.2f, 8.9f); lineTo(10.8f, 17.3f)
            lineTo(6.4f, 17.3f); lineTo(6.4f, 12.9f); close()
            moveTo(4.5f, 20f); horizontalLineTo(19.5f)
        }
    }

    val Italic: ImageVector by lazy {
        strokeIcon("EditorItalic") {
            moveTo(11f, 5.5f); horizontalLineTo(17f)
            moveTo(7f, 18.5f); horizontalLineTo(13f)
            moveTo(14f, 5.5f); lineTo(10f, 18.5f)
        }
    }

    val BulletList: ImageVector by lazy {
        strokeIcon("EditorBulletList") {
            moveTo(9.5f, 7f); horizontalLineTo(19.5f)
            moveTo(9.5f, 12f); horizontalLineTo(19.5f)
            moveTo(9.5f, 17f); horizontalLineTo(19.5f)
            circle(5.5f, 7f, 0.9f)
            circle(5.5f, 12f, 0.9f)
            circle(5.5f, 17f, 0.9f)
        }
    }

    val NumberedList: ImageVector by lazy {
        strokeIcon("EditorNumberedList") {
            moveTo(10.5f, 7f); horizontalLineTo(19.5f)
            moveTo(10.5f, 12f); horizontalLineTo(19.5f)
            moveTo(10.5f, 17f); horizontalLineTo(19.5f)
            moveTo(5f, 6f); lineTo(6.5f, 5f); verticalLineTo(9f)
            moveTo(4.8f, 13.5f); curveTo(4.8f, 12.5f, 7.2f, 12.5f, 7.2f, 13.8f)
            curveTo(7.2f, 15f, 4.8f, 16f, 4.8f, 17.5f); horizontalLineTo(7.4f)
        }
    }

    val Quote: ImageVector by lazy {
        strokeIcon("EditorQuote") {
            moveTo(5f, 5.5f); verticalLineTo(18.5f)
            moveTo(9.5f, 8f); horizontalLineTo(19f)
            moveTo(9.5f, 12f); horizontalLineTo(17.5f)
            moveTo(9.5f, 16f); horizontalLineTo(15.5f)
        }
    }

    val Callout: ImageVector by lazy {
        strokeIcon("EditorCallout") {
            roundedRect(4f, 4.5f, 20f, 19.5f, 3.5f)
            moveTo(12f, 8.5f); verticalLineTo(12.8f)
            moveTo(12f, 15.8f); verticalLineTo(16.2f)
        }
    }

    val Toggle: ImageVector by lazy {
        strokeIcon("EditorToggle") {
            moveTo(6f, 7f); lineTo(11f, 10.5f); lineTo(6f, 14f); close()
            moveTo(13.5f, 8.5f); horizontalLineTo(19.5f)
            moveTo(13.5f, 12.5f); horizontalLineTo(19.5f)
            moveTo(8f, 17f); horizontalLineTo(19.5f)
        }
    }

    val DividerLine: ImageVector by lazy {
        strokeIcon("EditorDividerLine") {
            moveTo(4f, 12f); horizontalLineTo(20f)
            moveTo(7f, 7f); horizontalLineTo(17f)
            moveTo(7f, 17f); horizontalLineTo(17f)
        }
    }

    val IndentIncrease: ImageVector by lazy {
        strokeIcon("EditorIndentIncrease") {
            moveTo(4.5f, 6.5f); horizontalLineTo(19.5f)
            moveTo(10.5f, 10.5f); horizontalLineTo(19.5f)
            moveTo(10.5f, 14.5f); horizontalLineTo(19.5f)
            moveTo(4.5f, 18.5f); horizontalLineTo(19.5f)
            moveTo(4.5f, 9.5f); lineTo(7.5f, 12.5f); lineTo(4.5f, 15.5f)
        }
    }

    val IndentDecrease: ImageVector by lazy {
        strokeIcon("EditorIndentDecrease") {
            moveTo(4.5f, 6.5f); horizontalLineTo(19.5f)
            moveTo(10.5f, 10.5f); horizontalLineTo(19.5f)
            moveTo(10.5f, 14.5f); horizontalLineTo(19.5f)
            moveTo(4.5f, 18.5f); horizontalLineTo(19.5f)
            moveTo(7.5f, 9.5f); lineTo(4.5f, 12.5f); lineTo(7.5f, 15.5f)
        }
    }

    val MoveUp: ImageVector by lazy {
        strokeIcon("EditorMoveUp") {
            moveTo(12f, 18.5f); verticalLineTo(5.5f)
            moveTo(7f, 10.5f); lineTo(12f, 5.5f); lineTo(17f, 10.5f)
        }
    }

    val MoveDown: ImageVector by lazy {
        strokeIcon("EditorMoveDown") {
            moveTo(12f, 5.5f); verticalLineTo(18.5f)
            moveTo(7f, 13.5f); lineTo(12f, 18.5f); lineTo(17f, 13.5f)
        }
    }

    val Copy: ImageVector by lazy {
        strokeIcon("EditorCopy") {
            roundedRect(8.5f, 8.5f, 19f, 19.5f, 2f)
            moveTo(5.5f, 15.5f); verticalLineTo(6.5f)
            curveTo(5.5f, 5.4f, 6.4f, 4.5f, 7.5f, 4.5f)
            horizontalLineTo(15.5f)
        }
    }

    val SelectAll: ImageVector by lazy {
        strokeIcon("EditorSelectAll") {
            roundedRect(4.5f, 4.5f, 19.5f, 19.5f, 3f)
            moveTo(8.5f, 12.2f); lineTo(11f, 14.7f); lineTo(16f, 9.5f)
        }
    }

    private fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.75f,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

    private fun fillIcon(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
            pathBuilder = block,
        )
    }.build()

    private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
        moveTo(left + r, top)
        horizontalLineTo(right - r)
        curveTo(right - r * 0.45f, top, right, top + r * 0.45f, right, top + r)
        verticalLineTo(bottom - r)
        curveTo(right, bottom - r * 0.45f, right - r * 0.45f, bottom, right - r, bottom)
        horizontalLineTo(left + r)
        curveTo(left + r * 0.45f, bottom, left, bottom - r * 0.45f, left, bottom - r)
        verticalLineTo(top + r)
        curveTo(left, top + r * 0.45f, left + r * 0.45f, top, left + r, top)
        close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        val k = r * 0.5522847f
        moveTo(cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        close()
    }
}
