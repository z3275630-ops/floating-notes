package io.github.z3275630ops.floatingnotes.feature.capture

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.z3275630ops.floatingnotes.data.local.AttachmentKind
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.LocalAttachment

class AttachmentFileProvider : FileProvider()

@Composable
internal fun AttachmentThumbnail(store: AttachmentStore, id: String, type: String, modifier: Modifier) {
    val preview by produceState<Bitmap?>(null, id) { value = store.thumbnail(id) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            val label = when (type) {
                "image" -> "图片"
                "audio" -> "音频"
                "video" -> "视频"
                else -> "附件"
            }
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun AttachmentRow(
    store: AttachmentStore,
    id: String,
    enabled: Boolean,
    insertTranscript: (String) -> Unit,
    remove: () -> Unit,
) {
    val context = LocalContext.current
    var loaded by remember(id) { mutableStateOf(false) }
    val attachment by produceState<LocalAttachment?>(null, id) {
        value = store.find(id)
        loaded = true
    }
    var openFailed by remember(id) { mutableStateOf(false) }
    val item = attachment
    var showTranscription by remember(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item != null) {
                val type = AttachmentKind.fromMime(item.mime)?.blockType.orEmpty()
                AttachmentThumbnail(store, item.id, type, Modifier.size(52.dp))
            }
            TextButton(
                onClick = {
                    openFailed = try {
                        if (item == null) return@TextButton
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.attachments", store.original(item), item.name,
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        false
                    } catch (_: RuntimeException) {
                        true
                    }
                },
                enabled = enabled && item != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(item?.name ?: if (loaded) "附件不可用" else "正在读取附件", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = remove, enabled = enabled) { Text("移除") }
        }
        if (AttachmentKind.fromMime(item?.mime) == AttachmentKind.Audio) {
            TextButton(onClick = { showTranscription = true }, enabled = enabled) { Text("转写") }
        }
        if (openFailed) Text("无法打开该附件，请检查是否有应用支持此格式。", color = MaterialTheme.colorScheme.error)
    }
    if (showTranscription && item != null) {
        TranscriptionDialog(store, item, { showTranscription = false }, insertTranscript)
    }
}
