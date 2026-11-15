package io.github.z3275630ops.floatingnotes.feature.share

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSwitch
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ShareCardSheet(
    note: NoteEntity,
    tagNames: List<String>,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var options by remember(note.id) { mutableStateOf(ShareCardOptions()) }
    var renderedPages by remember(note.id) { mutableStateOf<List<RenderedShareCard>>(emptyList()) }
    var prepared by remember(note.id) { mutableStateOf<ShareCardPreparedContent?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var shareStatus by remember(note.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(note.id, note.revision, options, tagNames) {
        rendering = true
        val content = ShareCardPaginator.buildPreparedContent(
            note = note,
            tagNames = tagNames,
            options = options,
        )
        prepared = content
        renderedPages = withContext(Dispatchers.Default) {
            ShareCardRenderer.renderAllPages(
                context = context,
                noteId = note.id,
                prepared = content,
                template = options.template,
            )
        }
        rendering = false
    }

    // Q055: Never treat chooser cancel / back press as a successful external share!
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        shareStatus = if (result.resultCode == Activity.RESULT_OK) {
            "已在系统面板中交给目标应用"
        } else {
            "已取消系统分享，图片未外发（仅保存在本机临时预览区）"
        }
    }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        modifier = Modifier.testTag("share-card-sheet"),
        title = "分享卡片预览",
        subtitle = "本地生成卡片 · 确认后调用系统分享",
        leadingLabel = "关闭",
        onLeadingClick = onDismiss,
        maxHeightFraction = 0.86f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // Template chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShareCardTemplate.entries.forEach { tpl ->
                    MintChip(
                        label = tpl.label,
                        selected = options.template == tpl,
                        dark = dark,
                        modifier = Modifier.testTag("share-template-${tpl.name}"),
                        onClick = { options = options.copy(template = tpl) },
                    )
                }
            }

            // Unsupported media warning or pagination notice (S19 / Q056)
            val currentPrepared = prepared
            if (currentPrepared != null && (currentPrepared.unsupportedMediaCount > 0 || currentPrepared.pages.size > 1)) {
                MintGroupedSection(dark = dark, containerColor = mintSecondaryFill(dark)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (currentPrepared.pages.size > 1) {
                            Text(
                                text = "内容较长，已自动拆分为 ${currentPrepared.pages.size} 张有序卡片（每页均保留来源标识）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface,
                                modifier = Modifier.testTag("share-pagination-notice"),
                            )
                        }
                        if (currentPrepared.unsupportedMediaCount > 0) {
                            Text(
                                text = "所含 ${currentPrepared.unsupportedMediaCount} 个音频/视频附件不支持直接渲染到分享图，已自动略过。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.testTag("share-unsupported-media-notice"),
                            )
                        }
                    }
                }
            }

            // Rendered image preview strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(mintSecondaryFill(dark))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (rendering && renderedPages.isEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 40.dp),
                    ) {
                        MintActivityIndicator()
                        Text("正在本机生成卡片预览…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        renderedPages.forEach { page ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Image(
                                    bitmap = page.bitmap.asImageBitmap(),
                                    contentDescription = "分享卡片第 ${page.pageIndex} 页",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .width(248.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .testTag("share-preview-page-${page.pageIndex}"),
                                )
                                if (renderedPages.size > 1) {
                                    Text(
                                        text = "第 ${page.pageIndex} / ${renderedPages.size} 页",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Privacy & visible fields (Q056)
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                MintGroupedRow(
                    label = "显示标题",
                    leadingIcon = MintIcons.Records,
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = options.showTitle,
                            onCheckedChange = { options = options.copy(showTitle = it) },
                            dark = dark,
                        )
                    },
                    onClick = { options = options.copy(showTitle = !options.showTitle) },
                )
                MintGroupedDivider(dark = dark, startIndent = 48.dp)
                MintGroupedRow(
                    label = "显示记录时间",
                    leadingIcon = MintIcons.Calendar,
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = options.showTimestamp,
                            onCheckedChange = { options = options.copy(showTimestamp = it) },
                            dark = dark,
                        )
                    },
                    onClick = { options = options.copy(showTimestamp = !options.showTimestamp) },
                )
                MintGroupedDivider(dark = dark, startIndent = 48.dp)
                MintGroupedRow(
                    label = "显示标签",
                    leadingIcon = MintIcons.Tag,
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = options.showTags,
                            onCheckedChange = { options = options.copy(showTags = it) },
                            dark = dark,
                        )
                    },
                    onClick = { options = options.copy(showTags = !options.showTags) },
                )
                MintGroupedDivider(dark = dark, startIndent = 48.dp)
                MintGroupedRow(
                    label = "包含原出处/来源链接",
                    subtitle = "默认关闭，开启后才在卡片底部附加来源链接或生成方式",
                    leadingIcon = MintIcons.Link,
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = options.includeSourceMetadata,
                            onCheckedChange = { options = options.copy(includeSourceMetadata = it) },
                            dark = dark,
                            modifier = Modifier.testTag("share-include-source-toggle"),
                        )
                    },
                    onClick = { options = options.copy(includeSourceMetadata = !options.includeSourceMetadata) },
                )
            }

            shareStatus?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = scheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .testTag("share-status-text"),
                )
            }
            }
            Spacer(modifier = Modifier.height(10.dp))
            MintPillButton(
                label = if (renderedPages.size > 1) "打开系统分享（共 ${renderedPages.size} 张图）" else "打开系统分享",
                icon = MintIcons.Share,
                primary = true,
                enabled = !rendering && renderedPages.isNotEmpty(),
                dark = dark,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share-open-system-chooser"),
                onClick = {
                    val uris = ArrayList(renderedPages.map { it.contentUri })
                    val sendIntent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uris.first())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/png"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    val chooser = Intent.createChooser(sendIntent, "分享卡片图片")
                    shareLauncher.launch(chooser)
                },
            )
        }
    }
}
