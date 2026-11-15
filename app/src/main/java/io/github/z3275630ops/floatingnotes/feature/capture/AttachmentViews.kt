package io.github.z3275630ops.floatingnotes.feature.capture

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.z3275630ops.floatingnotes.data.local.AttachmentKind
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.LocalAttachment
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill

class AttachmentFileProvider : FileProvider()

@Composable
internal fun AttachmentThumbnail(store: AttachmentStore, id: String, type: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val preview by produceState<Bitmap?>(null, id) { value = store.thumbnail(id) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .clip(shape)
            .background(mintSecondaryFill(dark)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val (icon, label) = when (type) {
                "image" -> MintIcons.Image to "图片"
                "audio" -> MintIcons.Audio to "音频"
                "video" -> MintIcons.Video to "视频"
                else -> MintIcons.Link to "附件"
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
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
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    var loaded by remember(id) { mutableStateOf(false) }
    val attachment by produceState<LocalAttachment?>(null, id) {
        value = store.find(id)
        loaded = true
    }
    var openFailed by remember(id) { mutableStateOf(false) }
    val item = attachment
    var showTranscription by remember(id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val cardColor = mintCardSurface(dark)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(cardColor, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item != null) {
                val type = AttachmentKind.fromMime(item.mime)?.blockType.orEmpty()
                AttachmentThumbnail(store, item.id, type, Modifier.size(48.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = enabled && item != null,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) {
                        openFailed = try {
                            if (item == null) return@clickable
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.attachments", store.original(item), item.name,
                            )
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, item.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                            false
                        } catch (_: RuntimeException) {
                            true
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item?.name ?: if (loaded) "附件不可用" else "正在读取附件",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.mime,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (AttachmentKind.fromMime(item?.mime) == AttachmentKind.Audio) {
                TextButton(
                    onClick = { showTranscription = true },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "转写",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
            TextButton(
                onClick = remove,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "移除",
                    color = scheme.error,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
        if (openFailed) {
            Text(
                text = "无法打开该附件，请检查是否有应用支持此格式。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }
    }
    if (showTranscription && item != null) {
        TranscriptionDialog(store, item, { showTranscription = false }, insertTranscript)
    }
}
