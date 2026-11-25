package io.github.z3275630ops.floatingnotes.core.model

import androidx.compose.ui.text.TextRange
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiff
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.LocalTextIntelligence
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.feature.capture.EditorBlockOps
import io.github.z3275630ops.floatingnotes.feature.capture.EditorSnapshot
import io.github.z3275630ops.floatingnotes.feature.capture.EditorUndoStack
import io.github.z3275630ops.floatingnotes.feature.capture.SlashCommandCatalog
import io.github.z3275630ops.floatingnotes.feature.capture.UndoRedoOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentBlockEditorTest {

    @Test
    fun noteDocumentRoundTripSupportsAllBlockTypesIndentAndMarks() {
        val original = NoteDocument(
            blocks = listOf(
                NoteBlock.heading(
                    level = 2,
                    text = "项目周报",
                    marks = listOf(NoteTextMark(NoteMarkType.BOLD, 0, 2)),
                ),
                NoteBlock.paragraph(
                    text = "正文加粗与高亮片段",
                    indent = 1,
                    marks = listOf(
                        NoteTextMark(NoteMarkType.BOLD, 2, 4),
                        NoteTextMark(NoteMarkType.HIGHLIGHT, 5, 7),
                        NoteTextMark(NoteMarkType.ITALIC, 7, 9),
                    ),
                ),
                NoteBlock.bullet(text = "无序项", indent = 2),
                NoteBlock.numbered(text = "有序项", indent = 1),
                NoteBlock.checkbox(text = "未完成待办", checked = false, dueDate = "2026-12-01", indent = 1),
                NoteBlock.quote(text = "引用金句"),
                NoteBlock.callout(text = "记得备份", tone = "warn", indent = 1),
                NoteBlock.toggle(title = "展开细节", text = "内部正文第一行\n第二行", collapsed = true, indent = 1),
                NoteBlock.divider(style = "dashed"),
                NoteBlock.reference("note-ref-1", revision = 1L, label = "关联笔记"),
            ),
        )

        val encoded = original.encode()
        val decoded = NoteDocument.decode(encoded)

        assertEquals(original.blocks.size, decoded.blocks.size)
        decoded.blocks.zip(original.blocks).forEach { (actual, expected) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.type, actual.type)
            assertEquals(expected.text, actual.text)
            assertEquals(expected.level, actual.level)
            assertEquals(expected.indent, actual.indent)
            assertEquals(expected.marks, actual.marks)
            assertEquals(expected.tone, actual.tone)
            assertEquals(expected.title, actual.title)
            assertEquals(expected.collapsed, actual.collapsed)
            assertEquals(expected.dividerStyle, actual.dividerStyle)
            assertEquals(expected.checked, actual.checked)
            assertEquals(expected.dueDate, actual.dueDate)
            assertEquals(expected.referenceTarget, actual.referenceTarget)
        }

        // All text-bearing block types remain editable even when marks are present
        val editableTypes = setOf("paragraph", "heading", "bullet", "numbered", "checkbox", "quote", "callout", "toggle")
        decoded.blocks.filter { it.type in editableTypes }.forEach { block ->
            assertTrue("Expected ${block.type} to be editable even with marks", block.editable)
        }
        assertFalse(decoded.blocks.first { it.type == "divider" }.editable)
        assertFalse(decoded.blocks.first { it.type == "reference" }.editable)
    }

    @Test
    fun legacyPlainCheckboxJsonOmitsDefaultIndentAndEmptyMarks() {
        val plainCheckbox = NoteBlock.checkbox(
            text = "交水电费",
            checked = false,
            dueDate = "2026-11-30",
        )
        val json = NoteDocument(listOf(plainCheckbox)).encode()
        assertFalse("Plain checkbox should omit zero indent", json.contains("\"indent\""))
        assertFalse("Plain checkbox should omit empty marks", json.contains("\"marks\""))

        // Legacy payload missing newly added optional fields decodes cleanly with safe defaults
        val legacyJson = """
            {
              "document_schema_version": 1,
              "blocks": [
                {"id": "b1", "type": "paragraph", "text": "老笔记内容"},
                {"id": "b2", "type": "heading", "level": 9, "text": "超界标题"},
                {"id": "b3", "type": "callout", "tone": "unknown_tone", "text": "兼容未知配色"},
                {"id": "b4", "type": "divider", "style": "dotted"}
              ]
            }
        """.trimIndent()
        val decoded = NoteDocument.decode(legacyJson)
        assertEquals(4, decoded.blocks.size)
        assertEquals(0, decoded.blocks[0].indent)
        assertTrue(decoded.blocks[0].editable)
        assertEquals(3, decoded.blocks[1].level)
        assertEquals("info", decoded.blocks[2].tone)
        assertEquals("solid", decoded.blocks[3].dividerStyle)
    }

    @Test
    fun marksRangeAdjustsAcrossEditsSplitsMergesAndUtf16EmojiBoundaries() {
        // Basic toggle on + off + merge adjacent
        val baseText = "你好世界"
        val bolded = toggleTextMark(emptyList(), NoteMarkType.BOLD, 0, 2, baseText)
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 0, 2)), bolded)
        val mergedAdjacent = toggleTextMark(bolded, NoteMarkType.BOLD, 2, 4, baseText)
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 0, 4)), mergedAdjacent)
        val toggledOff = toggleTextMark(mergedAdjacent, NoteMarkType.BOLD, 0, 4, baseText)
        assertTrue(toggledOff.isEmpty())

        // Surrogate-pair emoji takes 2 UTF-16 code units; toggling inside surrogate avoids invalid split
        val emojiText = "🎉快乐笔记"
        val emojiBold = toggleTextMark(emptyList(), NoteMarkType.HIGHLIGHT, 1, 4, emojiText)
        assertEquals(listOf(NoteTextMark(NoteMarkType.HIGHLIGHT, 0, 4)), emojiBold)

        // Insert prefix shifts mark right
        val initialMarks = listOf(NoteTextMark(NoteMarkType.BOLD, 2, 6))
        val insertedBefore = adjustMarksForTextEdit(
            marks = initialMarks,
            oldText = "今天写代码呀",
            newText = "上午今天写代码呀",
            cursorAfterEdit = 2,
        )
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 4, 8)), insertedBefore)

        // Delete overlapping slice trims mark safely
        val deletedOverlap = adjustMarksForTextEdit(
            marks = listOf(NoteTextMark(NoteMarkType.ITALIC, 1, 5)),
            oldText = "abcdef",
            newText = "abef",
            cursorAfterEdit = 2,
        )
        assertEquals(listOf(NoteTextMark(NoteMarkType.ITALIC, 1, 3)), deletedOverlap)

        // Split & merge across paragraphs preserve exact offsets
        val leftStr = "前半"
        val rightStr = "后半"
        val fullMarks = listOf(NoteTextMark(NoteMarkType.BOLD, 1, 3))
        val (leftMarks, rightMarks) = splitMarksAt(fullMarks, 2, leftStr, rightStr)
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 1, 2)), leftMarks)
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 0, 1)), rightMarks)

        val remerged = mergeMarks(leftMarks, leftStr.length, rightMarks, leftStr + rightStr)
        assertEquals(fullMarks, remerged)
    }

    @Test
    fun goldenEnterRulesCoverHeadingListsTodoParagraphSplitAndToggle() {
        // Heading Enter at end creates regular paragraph
        val heading = NoteBlock.heading(level = 1, text = "大标题")
        val headingEnd = EditorBlockOps.applyEnter(listOf(heading), 0, "大标题", "")
        assertEquals(2, headingEnd.blocks.size)
        assertEquals("heading", headingEnd.blocks[0].type)
        assertEquals("paragraph", headingEnd.blocks[1].type)
        assertEquals(headingEnd.blocks[1].id, headingEnd.focusBlockId)

        // List & checkbox continue same type + inherit indent, unchecked on new checkbox
        val indentedTodo = NoteBlock.checkbox(text = "完成接口联调", checked = true, indent = 2)
        val todoEnter = EditorBlockOps.applyEnter(listOf(indentedTodo), 0, "完成接口联调", "")
        assertEquals(2, todoEnter.blocks.size)
        assertEquals("checkbox", todoEnter.blocks[1].type)
        assertEquals(2, todoEnter.blocks[1].indent)
        assertFalse(todoEnter.blocks[1].checked)

        // Empty list/todo Enter degrades to unindented paragraph
        val emptyBullet = NoteBlock.bullet(text = "", indent = 2)
        val emptyBulletEnter = EditorBlockOps.applyEnter(listOf(emptyBullet), 0, "", "")
        assertEquals(1, emptyBulletEnter.blocks.size)
        assertEquals("paragraph", emptyBulletEnter.blocks[0].type)
        assertEquals(0, emptyBulletEnter.blocks[0].indent)

        // Paragraph mid-split carries marks onto both sides
        val markedPara = NoteBlock.paragraph(
            text = "加粗一段文字",
            marks = listOf(NoteTextMark(NoteMarkType.BOLD, 1, 5)),
        )
        val split = EditorBlockOps.applyEnter(listOf(markedPara), 0, "加粗一", "段文字")
        assertEquals(2, split.blocks.size)
        assertEquals("加粗一", split.blocks[0].text)
        assertEquals("段文字", split.blocks[1].text)
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 1, 3)), split.blocks[0].marks)
        assertEquals(listOf(NoteTextMark(NoteMarkType.BOLD, 0, 2)), split.blocks[1].marks)

        // Toggle title Enter unfolds toggle and focuses body
        val collapsedToggle = NoteBlock.toggle(title = "章节", text = "隐藏内容", collapsed = true)
        val titleEnter = EditorBlockOps.applyEnter(listOf(collapsedToggle), 0, "章节", "", isToggleTitle = true)
        assertEquals(1, titleEnter.blocks.size)
        assertFalse(titleEnter.blocks[0].collapsed)
        assertFalse(titleEnter.focusToggleTitle)
    }

    @Test
    fun goldenBackspaceAtStartDegradesUnindentsAndMergesUpward() {
        val h1 = NoteBlock.heading(level = 2, text = "标题")
        val bullet = NoteBlock.bullet(text = "列表条目", indent = 1)
        val indentedPara = NoteBlock.paragraph(text = "缩进段落", indent = 1)
        val topPara = NoteBlock.paragraph(
            text = "第一段",
            marks = listOf(NoteTextMark(NoteMarkType.BOLD, 0, 3)),
        )
        val secondPara = NoteBlock.paragraph(
            text = "第二段",
            marks = listOf(NoteTextMark(NoteMarkType.HIGHLIGHT, 0, 2)),
        )

        // Step 1: non-paragraph (bullet) degrades to paragraph first
        val step1 = EditorBlockOps.applyBackspaceAtStart(listOf(h1, bullet), 1)
        assertNotNull(step1)
        assertEquals(2, step1!!.blocks.size)
        assertEquals("paragraph", step1.blocks[1].type)
        assertEquals(0, step1.blocks[1].indent)

        // Step 2: indented paragraph reduces indent before merging
        val step2 = EditorBlockOps.applyBackspaceAtStart(listOf(h1, indentedPara), 1)
        assertNotNull(step2)
        assertEquals(2, step2!!.blocks.size)
        assertEquals(0, step2.blocks[1].indent)

        // Step 3: unindented paragraph at index 1 merges upward preserving marks and join offset
        val step3 = EditorBlockOps.applyBackspaceAtStart(listOf(topPara, secondPara), 1)
        assertNotNull(step3)
        assertEquals(1, step3!!.blocks.size)
        assertEquals("第一段第二段", step3.blocks[0].text)
        assertEquals(3, step3.cursorOffset)
        assertEquals(topPara.id, step3.focusBlockId)
        assertEquals(
            listOf(
                NoteTextMark(NoteMarkType.BOLD, 0, 3),
                NoteTextMark(NoteMarkType.HIGHLIGHT, 3, 5),
            ),
            step3.blocks[0].marks,
        )
    }

    @Test
    fun markdownPrefixAndSlashShortcutsWorkPredictably() {
        val base = NoteBlock.paragraph()
        val prefixes = listOf(
            "# 标题一" to Triple("heading", 1, "标题一"),
            "## 标题二" to Triple("heading", 2, "标题二"),
            "### 标题三" to Triple("heading", 3, "标题三"),
            "- 列表" to Triple("bullet", 1, "列表"),
            "1. 编号" to Triple("numbered", 1, "编号"),
            "[] 待办" to Triple("checkbox", 1, "待办"),
            "[ ] 空格待办" to Triple("checkbox", 1, "空格待办"),
            "> 名言" to Triple("quote", 1, "名言"),
        )
        prefixes.forEach { (typed, expected) ->
            val outcome = EditorBlockOps.tryApplyMarkdownPrefix(listOf(base), 0, typed, typed.length)
            assertNotNull("Expected markdown prefix match for '$typed'", outcome)
            assertEquals(expected.first, outcome!!.blocks[0].type)
            assertEquals(expected.second, outcome.blocks[0].level)
            assertEquals(base.id, outcome.blocks[0].id)
            assertEquals(expected.third, outcome.blocks[0].text)
        }

        // Divider shortcut emits divider + trailing editable paragraph
        val divOutcome = EditorBlockOps.tryApplyMarkdownPrefix(listOf(base), 0, "---", 3)
        assertNotNull(divOutcome)
        assertEquals(listOf("divider", "paragraph"), divOutcome!!.blocks.map { it.type })
        assertEquals(divOutcome.blocks[1].id, divOutcome.focusBlockId)

        // Slash detection ignores IME composition and mid-word slashes
        assertNull(EditorBlockOps.detectSlashCommand("/bt", TextRange(3), TextRange(0, 3)))
        assertNull(EditorBlockOps.detectSlashCommand("http://", TextRange(7), null))
        val trigger = EditorBlockOps.detectSlashCommand("内容 /bt", TextRange(6), null)
        assertNotNull(trigger)
        assertEquals(3, trigger!!.triggerIndex)
        assertEquals("bt", trigger.query)

        assertTrue(SlashCommandCatalog.filter("bt").any { it.targetType == "heading" && it.headingLevel == 1 })
        assertTrue(SlashCommandCatalog.filter("db").any { it.targetType == "checkbox" })
        assertTrue(SlashCommandCatalog.filter("yy").any { it.targetType == "quote" })
        assertTrue(SlashCommandCatalog.filter("fg").any { it.targetType == "divider" })
        assertTrue(SlashCommandCatalog.filter("ts").any { it.targetType == "callout" })
        assertTrue(SlashCommandCatalog.filter("zd").any { it.targetType == "toggle" })

        val slashApplied = EditorBlockOps.applySlashCommand(
            blocks = listOf(NoteBlock.paragraph("已写内容 /db").copy(id = "p-keep")),
            blockIndex = 0,
            trigger = trigger.copy(triggerIndex = 5, cursorIndex = 8, query = "db"),
            command = SlashCommandCatalog.filter("db").first(),
        )
        assertEquals(2, slashApplied.blocks.size)
        assertEquals("已写内容", slashApplied.blocks[0].text)
        assertEquals("checkbox", slashApplied.blocks[1].type)
    }

    @Test
    fun undoRedoStackPreservesBlockIdsAcrossStructuralEditsAndBoundaries() {
        val stack = EditorUndoStack(coalesceWindowMs = 500L)
        val p1 = NoteBlock.paragraph("第一段")
        val p2 = NoteBlock.checkbox("待办项")
        val initial = EditorSnapshot(listOf(p1, p2), focusedBlockId = p2.id, cursorOffset = 3)

        // Delete p2
        stack.recordStructuralChange(initial)
        val afterDeleteOutcome = EditorBlockOps.deleteBlock(initial.blocks, 1)
        val afterDelete = EditorSnapshot(afterDeleteOutcome.blocks, afterDeleteOutcome.focusBlockId, afterDeleteOutcome.cursorOffset)
        assertEquals(1, afterDelete.blocks.size)

        // Undo restores exact p2 block ID
        val undo1 = stack.undo(afterDelete) as UndoRedoOutcome.Applied
        assertEquals(2, undo1.snapshot.blocks.size)
        assertEquals(p2.id, undo1.snapshot.blocks[1].id)
        assertEquals("checkbox", undo1.snapshot.blocks[1].type)

        // Further undo hits boundary cleanly
        val atStart = stack.undo(undo1.snapshot)
        assertTrue(atStart is UndoRedoOutcome.AtBoundary)

        // Redo re-applies delete, then hits boundary cleanly
        val redo1 = stack.redo(undo1.snapshot) as UndoRedoOutcome.Applied
        assertEquals(1, redo1.snapshot.blocks.size)
        val atEnd = stack.redo(redo1.snapshot)
        assertTrue(atEnd is UndoRedoOutcome.AtBoundary)

        // Typing coalesces within window and respects forceBreak
        val t0 = EditorSnapshot(listOf(p1), p1.id, 0)
        stack.recordTypingChange(t0, nowMs = 1_000L, forceBreak = false)
        stack.recordTypingChange(EditorSnapshot(listOf(p1.copy(text = "第一段a")), p1.id, 4), nowMs = 1_100L, forceBreak = false)
        stack.recordTypingChange(EditorSnapshot(listOf(p1.copy(text = "第一段ab ")), p1.id, 6), nowMs = 1_200L, forceBreak = true)
        val undoneCoalesced = stack.undo(EditorSnapshot(listOf(p1.copy(text = "第一段ab c")), p1.id, 7)) as UndoRedoOutcome.Applied
        assertEquals("第一段ab ", undoneCoalesced.snapshot.blocks[0].text)
        val undoneBeforeCoalesced = stack.undo(undoneCoalesced.snapshot) as UndoRedoOutcome.Applied
        assertEquals("第一段", undoneBeforeCoalesced.snapshot.blocks[0].text)
    }

    @Test
    fun discoveryChunkingMergeAndVersionDiffHandleCalloutToggleDividerAndMarks() {
        val callout = NoteBlock.callout(text = "核心提示语", tone = "tip")
        val toggle = NoteBlock.toggle(title = "折叠标题词", text = "折叠隐藏详情", collapsed = true)
        val divider = NoteBlock.divider(style = "dashed")
        val marked = NoteBlock.paragraph(
            text = "包含加粗与高亮",
            marks = listOf(
                NoteTextMark(NoteMarkType.BOLD, 2, 4),
                NoteTextMark(NoteMarkType.HIGHLIGHT, 5, 7),
            ),
        )
        val doc = NoteDocument(listOf(callout, toggle, divider, marked))
        val encoded = doc.encode()

        val noteEntity = NoteEntity(
            id = "n-rich",
            title = "富文本块测试",
            documentJson = encoded,
            searchText = "核心提示语\n折叠标题词\n折叠隐藏详情\n包含加粗与高亮",
            sourceType = "manual",
            sourceUrl = null,
            createdAt = "2026-11-24T10:00:00Z",
            updatedAt = "2026-11-24T10:05:00Z",
            revision = 1L,
            contentHash = "hash-rich",
            lastDraftId = "draft-rich",
            committedSeq = 1L,
        )

        val chunks = LocalRetrievalEngine.buildChunks(noteEntity)
        val chunkTexts = chunks.joinToString("\n") { it.excerpt }
        assertTrue(chunkTexts.contains("核心提示语"))
        assertTrue(chunkTexts.contains("折叠标题词"))
        assertTrue(chunkTexts.contains("折叠隐藏详情"))
        assertFalse(chunks.any { it.blockIdsJson.contains(divider.id) })

        val other = noteEntity.copy(
            id = "n-other",
            documentJson = NoteDocument(listOf(NoteBlock.callout(text = "核心提示语", tone = "tip"))).encode(),
        )
        val merged = LocalTextIntelligence.buildMergedContent(noteEntity, listOf(other))
        val mergedBody = merged.blocks.joinToString("\n") { it.searchableText }
        assertTrue(mergedBody.contains("折叠标题词"))
        assertTrue(mergedBody.contains("核心提示语"))
        assertEquals(1, merged.blocks.count { it.type == "callout" })

        val diff = VersionDiff.diffDocuments(
            noteId = "n-rich",
            targetRevision = 1L,
            targetTitle = "富文本块测试",
            targetDocumentJson = NoteDocument(listOf(NoteBlock.paragraph("原始段落"))).encode(),
            targetAttachmentRefsJson = null,
            targetOrigin = "auto",
            targetCreatedAt = "2026-11-24T09:59:00Z",
            currentRevision = 2L,
            currentTitle = "富文本块测试",
            currentDocumentJson = encoded,
            resolveAttachmentLabel = { null },
        )
        val diffText = diff.lines.joinToString("\n") { it.text }
        assertTrue(diffText.contains("[提示(技巧)] 核心提示语"))
        assertTrue(diffText.contains("[折叠▸] 折叠标题词"))
        assertTrue(diffText.contains("——— 分割线（虚线）———"))
        assertTrue(diffText.contains("加粗「加粗」、高亮「高亮」"))
    }

    @Test
    fun tapBlankCanvasCreatesNewParagraphUnlessTailIsAlreadyEmptyPlainParagraph() {
        // Non-empty paragraph -> appends a brand-new empty paragraph and focuses it at 0
        val nonEmpty = listOf(NoteBlock.paragraph("已有内容"))
        val fromNonEmpty = EditorBlockOps.tapBlankCanvas(nonEmpty)
        assertEquals(2, fromNonEmpty.blocks.size)
        assertEquals("paragraph", fromNonEmpty.blocks[1].type)
        assertEquals("", fromNonEmpty.blocks[1].text)
        assertEquals(fromNonEmpty.blocks[1].id, fromNonEmpty.focusBlockId)
        assertEquals(0, fromNonEmpty.cursorOffset)

        // Trailing heading / checkbox / divider -> appends new empty paragraph below
        listOf(
            NoteBlock.heading(1, ""),
            NoteBlock.checkbox("待办", checked = false),
            NoteBlock.divider(),
        ).forEach { tail ->
            val outcome = EditorBlockOps.tapBlankCanvas(listOf(tail))
            assertEquals(2, outcome.blocks.size)
            assertEquals("paragraph", outcome.blocks.last().type)
            assertEquals(outcome.blocks.last().id, outcome.focusBlockId)
        }

        // Trailing empty plain paragraph -> focuses directly without duplicating empty lines
        val emptyTail = NoteBlock.paragraph("")
        val reuseOutcome = EditorBlockOps.tapBlankCanvas(listOf(NoteBlock.paragraph("第一段"), emptyTail))
        assertEquals(2, reuseOutcome.blocks.size)
        assertEquals(emptyTail.id, reuseOutcome.focusBlockId)
        assertEquals(0, reuseOutcome.cursorOffset)
    }

    @Test
    fun multiSelectBatchOperationsConvertCopyAndDeleteBlocksCleanly() {
        val p1 = NoteBlock.paragraph("需要处理")
        val p2 = NoteBlock.bullet("列表项目")
        val ref = NoteBlock.reference("target-1", revision = 1L, label = "参考卡片")
        val blocks = listOf(p1, p2, ref)

        // Toggle individual and select all
        var selected = EditorBlockOps.toggleBlockSelection(emptySet(), p1.id)
        assertEquals(setOf(p1.id), selected)
        selected = EditorBlockOps.toggleBlockSelection(selected, p1.id)
        assertTrue(selected.isEmpty())

        val allIds = EditorBlockOps.selectAllBlocks(blocks)
        assertEquals(setOf(p1.id, p2.id, ref.id), allIds)

        // Batch toggle todo turns editable selections into checkboxes, leaving reference card intact
        val toTodo = EditorBlockOps.batchToggleTodo(blocks, setOf(p1.id, p2.id, ref.id))
        assertEquals(listOf("checkbox", "checkbox", "reference"), toTodo.blocks.map { it.type })
        assertEquals(p1.id, toTodo.blocks[0].id)

        // Calling batchToggleTodo again when all editable selections are checkboxes reverts to paragraph
        val backToPara = EditorBlockOps.batchToggleTodo(toTodo.blocks, setOf(p1.id, p2.id))
        assertEquals(listOf("paragraph", "paragraph", "reference"), backToPara.blocks.map { it.type })

        // Batch convert to divider adds trailing paragraph when list would end with divider
        val toDiv = EditorBlockOps.batchConvertType(listOf(p1, p2), setOf(p2.id), "divider")
        assertEquals(listOf("paragraph", "divider", "paragraph"), toDiv.blocks.map { it.type })

        // Batch copy joins non-empty block text in document order
        assertEquals("需要处理\n列表项目", EditorBlockOps.batchCopyText(blocks, setOf(p2.id, p1.id)))

        // Batch delete removes selected blocks and guarantees fallback empty paragraph if all deleted
        val partialDelete = EditorBlockOps.batchDeleteBlocks(blocks, setOf(p1.id))
        assertEquals(listOf(p2.id, ref.id), partialDelete.blocks.map { it.id })
        val fullDelete = EditorBlockOps.batchDeleteBlocks(blocks, allIds)
        assertEquals(1, fullDelete.blocks.size)
        assertEquals("paragraph", fullDelete.blocks[0].type)
        assertEquals("", fullDelete.blocks[0].text)
    }
}
