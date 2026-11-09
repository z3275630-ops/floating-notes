package io.github.z3275630ops.floatingnotes.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun ColumnScope.NoteEditor(
    session: EditorSession,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var confirmDiscard by remember { mutableStateOf(false) }
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
