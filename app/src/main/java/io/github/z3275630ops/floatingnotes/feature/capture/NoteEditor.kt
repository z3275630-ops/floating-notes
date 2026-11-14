package io.github.z3275630ops.floatingnotes.feature.capture

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    model: NotesViewModel,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    notes: List<NoteEntity> = emptyList(),
    backlinks: List<NoteEntity> = emptyList(),
    onOpenRelated: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) model.startRecording(session)
        else session.voiceStatus = "麦克风权限未开启，可在系统设置授权后重试"
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        model.importAttachments(session, it)
    }
    var showImport by remember { mutableStateOf(false) }
    var space by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showImport) {
        if (showImport) space = model.attachments.availableSpace()
    }
    if (showImport) AlertDialog(
        onDismissRequest = { showImport = false },
        title = { Text("添加附件") },
        text = {
            Text("可选择图片、音频或视频，每个最多 100 MB。" +
                (space?.let { "\n本机可用空间 ${it / 1_000_000} MB" } ?: ""))
        },
        confirmButton = {
            TextButton(onClick = {
                showImport = false
                try {
                    attachmentPicker.launch(arrayOf("image/*", "audio/*", "video/*"))
                } catch (_: android.content.ActivityNotFoundException) {
                    session.importStatus = "本机没有可用的文件选择器"
                    session.importFailed = true
                }
            }) { Text("选择文件") }
        },
        dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
    )
    Row {
        TextButton(onClick = { showImport = true }, enabled = !session.writing && !session.importing && !session.voiceBusy) {
            Text(if (session.importFailed) "重新选择" else "附件")
        }
        if (!session.recording && !session.voicePending) {
            TextButton(onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
                enabled = !session.writing && !session.importing && !session.voiceBusy) { Text("录音") }
        }
        if (onOpenRelated != null && (notes.any { it.id != session.draft.noteId } || backlinks.isNotEmpty())) {
            TextButton(onClick = { picker = "引用笔记" }, enabled = !session.writing) { Text("@ 引用") }
            if (backlinks.isNotEmpty()) TextButton(onClick = { picker = "引用此笔记的记录" }) {
                Text("反链 ${backlinks.size}")
            }
        }
    }
    if (session.recording) {
        val seconds = session.recordingMillis / 1000
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("录音 · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", Modifier.weight(1f))
            TextButton(onClick = { model.toggleRecordingPause(session) }) {
                Text(if (session.recordingPaused) "继续" else "暂停")
            }
            TextButton(onClick = { model.stopRecording(session) }) { Text("停止并保存") }
        }
    }
    session.voiceStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    if (session.voicePending && !session.voiceBusy) Row {
        TextButton(onClick = { model.retryRecording(session) }) { Text("重试保存") }
        TextButton(onClick = { model.discardPendingRecording(session) }) { Text("移除待存录音") }
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
                    } else if (block.attachmentId != null) {
                        AttachmentRow(
                            model.attachments, requireNotNull(block.attachmentId), !session.writing && !session.voiceBusy,
                            insertTranscript = { text -> session.insertTranscript(block, text) },
                        ) {
                            session.removeAttachment(block)
                        }
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
    session.importStatus?.let { status ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f),
                color = if (session.importFailed) scheme.error else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            if (session.importing) TextButton(onClick = { model.cancelImport(session) }) { Text("取消导入") }
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
            enabled = !session.writing && !session.importing && !session.voiceBusy && !session.voicePending && session.document().hasContent,
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
