package io.github.z3275630ops.floatingnotes.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesSearchField

@Composable
internal fun ColumnScope.NoteEditor(
    session: EditorSession,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    notes: List<NoteEntity> = emptyList(),
    backlinks: List<NoteEntity> = emptyList(),
    onOpenRelated: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var confirmDiscard by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    if (onOpenRelated != null && (notes.any { it.id != session.draft.noteId } || backlinks.isNotEmpty())) {
        Row {
            TextButton(onClick = { picker = "引用笔记" }, enabled = !session.writing) { Text("@ 引用") }
            if (backlinks.isNotEmpty()) TextButton(onClick = { picker = "引用此笔记的记录" }) {
                Text("反链 ${backlinks.size}")
            }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .background(scheme.surface, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        session.blocks.forEachIndexed { index, block ->
            key(block.id) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (block.type == "checkbox") {
                        Checkbox(
                            checked = block.checked,
                            onCheckedChange = { block.checked = it },
                            enabled = block.editable && !session.writing,
                            modifier = Modifier.testTag("checkbox-$index"),
                        )
                    }
                    if (block.editable) {
                        BasicTextField(
                            state = block.text,
                            enabled = !session.writing,
                            modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                                .testTag(if (index == 0) "capture-editor" else "block-editor-$index"),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                            cursorBrush = SolidColor(scheme.primary),
                            decorator = TextFieldDecorator { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (block.text.text.isEmpty()) Text(
                                        if (block.type == "checkbox") "待办事项" else "写下一个想法…",
                                        color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    inner()
                                }
                            },
                        )
                    } else if (block.referenceTarget != null && onOpenRelated != null) {
                        val target = notes.firstOrNull { it.id == block.referenceTarget }
                        TextButton(
                            onClick = { target?.let { onOpenRelated(it.id) } },
                            enabled = target != null && !session.writing,
                            modifier = Modifier.weight(1f),
                        ) { Text(target?.let(::referenceLabel) ?: "来源已删除或不可用") }
                        TextButton(onClick = { session.removeReference(block) }, enabled = !session.writing) { Text("移除") }
                    } else {
                        Text(block.text.text.toString().ifBlank { "只读区块 · ${block.type}" })
                    }
                }
            }
        }
    }
    if (session.status.isNotEmpty()) Text(
        session.status,
        color = if ("失败" in session.status) scheme.error else scheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = session::addCheckbox, enabled = !session.writing) { Text("添加待办") }
        TextButton(onClick = { confirmDiscard = true }, enabled = !session.writing) { Text("丢弃草稿") }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onSave,
            enabled = !session.writing && session.document().hasContent,
        ) { Text("保存") }
    }
    picker?.let { mode ->
        val filter = key(mode) { rememberTextFieldState() }
        val query = filter.text.toString()
        val candidates = if (mode == "引用笔记") notes.filter { it.id != session.draft.noteId } else backlinks
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(mode) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComposablesSearchField(filter, "筛选笔记")
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(candidates.filter { referenceLabel(it).contains(query, ignoreCase = true) }, key = { it.id }) { note ->
                            TextButton(onClick = {
                                picker = null
                                if (mode == "引用笔记") session.addReference(note) else onOpenRelated?.invoke(note.id)
                            }) { Text(referenceLabel(note)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("关闭") } },
        )
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("丢弃本次草稿？") },
        text = { Text("已保存到本机的笔记会保留。") },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("丢弃") }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
    )
}


internal fun referenceLabel(note: NoteEntity): String = note.title?.takeIf(String::isNotBlank)
    ?: note.searchText.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
    ?: "无标题笔记"
