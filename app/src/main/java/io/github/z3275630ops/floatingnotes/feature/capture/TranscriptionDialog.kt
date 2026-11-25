package io.github.z3275630ops.floatingnotes.feature.capture

import android.content.Context
import android.util.AtomicFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.LocalAttachment
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintCheckbox
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGlassDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAiAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal class TranscriptionStore(context: Context, private val audio: File) {
    private val model = OfflineSpeechModel(context)
    private val result = AtomicFile(File(audio.parentFile, "transcript.json"))

    suspend fun modelInstalled() = model.installed()

    suspend fun read(): Transcript? = withContext(Dispatchers.IO) {
        lock.withLock { readResult() }
    }

    private fun readResult(): Transcript? {
        if (!result.baseFile.isFile) return null
        return try {
            val json = JSONObject(result.readFully().decodeToString())
            if (!json.has("raw") || !json.has("formatted")) {
                result.delete()
                null
            } else {
                Transcript(json.getString("raw"), json.getString("formatted"))
            }
        } catch (_: Exception) {
            result.delete()
            null
        }
    }

    suspend fun run(onStatus: (String) -> Unit): Transcript = lock.withLock {
        withContext(Dispatchers.IO) { readResult() }?.let { return@withLock it }
        val directory = try {
            model.prepare { progress ->
                withContext(Dispatchers.Main.immediate) {
                    onStatus(progress?.let { "下载语音模型 · $it%" } ?: "正在校验并安装模型")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SpeechException) {
            throw error
        } catch (_: Exception) {
            throw SpeechException("语音模型下载失败，请联网后重试")
        }
        onStatus("正在离线转写")
        val transcript = OfflineTranscriber.transcribe(audio, directory) { progress ->
            withContext(Dispatchers.Main.immediate) { onStatus("正在离线转写 · $progress%") }
        }
        withContext(Dispatchers.IO) {
            val json = JSONObject().put("provider", OfflineSpeechModel.NAME)
                .put("raw", transcript.raw).put("formatted", transcript.formatted)
            val stream = result.startWrite()
            try {
                stream.write(json.toString().encodeToByteArray())
                stream.fd.sync()
                result.finishWrite(stream)
            } catch (error: Exception) {
                result.failWrite(stream)
                throw error
            }
        }
        transcript
    }

    private companion object {
        val lock = Mutex()
    }
}

@Composable
internal fun TranscriptionDialog(
    store: AttachmentStore,
    attachment: LocalAttachment,
    dismiss: () -> Unit,
    insert: (String) -> Boolean,
    aiCleanEnabled: Boolean = false,
    aiCleaning: Boolean = false,
    cleanedTranscriptPreview: String? = null,
    extractedTodoPreviews: List<String> = emptyList(),
    aiCleanNotice: String? = null,
    onRequestAiClean: (String) -> Unit = {},
    onAcceptAiClean: (String, List<String>) -> Unit = { _, _ -> },
    onDismissAiCleanPreview: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val aiAccent = mintAiAccent(dark)
    val transcription = remember(attachment.id) { TranscriptionStore(context, store.original(attachment)) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var installed by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var original by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var selectedTodos by remember(cleanedTranscriptPreview, extractedTodoPreviews) {
        mutableStateOf(extractedTodoPreviews.indices.toSet())
    }
    LaunchedEffect(transcription) {
        try {
            installed = transcription.modelInstalled()
            transcript = transcription.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "无法读取已有转写"
        } finally {
            loading = false
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        job?.cancel()
        if (job != null) status = "转写已暂停，可返回重试"
    }
    val text = transcript?.let { if (original) it.raw else it.formatted }
    MintGlassDialog(
        onDismissRequest = { if (job == null && !aiCleaning) dismiss() },
        dark = dark,
        dismissOnClickOutside = job == null && !aiCleaning,
        maxWidth = 480.dp,
        title = "离线转写",
        subtitle = attachment.name,
        actions = {
            MintPillButton(
                label = if (job == null) "关闭" else "取消",
                dark = dark,
                enabled = !aiCleaning,
                onClick = dismiss,
            )
            if (text != null) {
                MintPillButton(
                    label = "插入正文",
                    primary = cleanedTranscriptPreview == null,
                    icon = MintIcons.Check,
                    dark = dark,
                    enabled = !aiCleaning,
                    onClick = {
                        if (insert(text)) {
                            dismiss()
                        } else {
                            status = "笔记正在保存，请稍后再点“插入正文”"
                        }
                    },
                )
            } else {
                MintPillButton(
                    label = if (job != null) "转写中" else if (status != null) "重试" else if (installed) "开始转写" else "下载并转写",
                    primary = true,
                    icon = MintIcons.Audio,
                    dark = dark,
                    enabled = !loading && job == null,
                    onClick = {
                        status = "正在准备"
                        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            try {
                                transcript = transcription.run { status = it }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: SpeechException) {
                                status = error.message
                            } catch (_: Exception) {
                                status = "转写失败，录音已保留，可重试"
                            } catch (_: LinkageError) {
                                status = "当前设备无法加载离线语音引擎"
                            } finally {
                                installed = withContext(kotlinx.coroutines.NonCancellable) { transcription.modelInstalled() }
                                job = null
                            }
                        }
                    },
                )
            }
        },
    ) {
        if (text != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (original) "原始语音识别结果" else "已整理可读文本",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    MintPillButton(
                        label = if (original) "查看整理版" else "查看原文",
                        dark = dark,
                        onClick = { original = !original },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (cleanedTranscriptPreview != null) 140.dp else 240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (dark) Color(0xFF1A2A22) else Color(0xFFF3F8F4))
                        .border(0.9.dp, scheme.outline.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }

                // flomo 克制型语音整理入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (aiCleanEnabled) aiAccent.bg else mintSecondaryFill(dark))
                        .border(
                            0.6.dp,
                            if (aiCleanEnabled) aiAccent.fg.copy(alpha = 0.28f) else mintDividerColor(dark),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(
                            enabled = aiCleanEnabled && !aiCleaning,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { onRequestAiClean(text) },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("transcription-ai-clean-button"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (aiCleaning) {
                        MintActivityIndicator(color = aiAccent.fg, sizeDp = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在去口癖并提待办…",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = aiAccent.fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Icon(
                            imageVector = MintIcons.Sparkles,
                            contentDescription = null,
                            tint = if (aiCleanEnabled) aiAccent.fg else scheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (aiCleanEnabled) "AI 去口癖并提待办" else "AI 去口癖并提待办（未启用大模型）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (aiCleanEnabled) aiAccent.fg else scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (!aiCleanNotice.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(mintDestructiveColor(dark).copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("transcription-ai-clean-notice"),
                    ) {
                        Text(
                            text = aiCleanNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = mintDestructiveColor(dark),
                        )
                    }
                }

                AnimatedVisibility(visible = cleanedTranscriptPreview != null) {
                    val cleanedText = cleanedTranscriptPreview.orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(mintSecondaryFill(dark))
                            .border(0.7.dp, aiAccent.fg.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                            .testTag("transcription-ai-clean-preview"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "克制整理对比（保留原意，仅删口癖）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = aiAccent.fg,
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.surface.copy(alpha = 0.55f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = "原话",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurfaceVariant,
                            )
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.primaryContainer.copy(alpha = if (dark) 0.45f else 0.72f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = "整理后",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.primary,
                            )
                            Text(
                                text = cleanedText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                            )
                        }

                        if (extractedTodoPreviews.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "识别到的待办（勾选后随结果一起添加）：",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurfaceVariant,
                                )
                                extractedTodoPreviews.forEachIndexed { idx, todo ->
                                    val checked = idx in selectedTodos
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Checkbox,
                                                onClick = {
                                                    selectedTodos = if (checked) {
                                                        selectedTodos - idx
                                                    } else {
                                                        selectedTodos + idx
                                                    }
                                                },
                                            )
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        MintCheckbox(
                                            checked = checked,
                                            onCheckedChange = { next ->
                                                selectedTodos = if (next) selectedTodos + idx else selectedTodos - idx
                                            },
                                            dark = dark,
                                        )
                                        Text(
                                            text = todo,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MintPillButton(
                                label = "保留原话",
                                primary = false,
                                dark = dark,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("transcription-keep-original-button"),
                                onClick = onDismissAiCleanPreview,
                            )
                            MintPillButton(
                                label = if (selectedTodos.isNotEmpty()) "采用整理结果并添加待办" else "采用整理结果",
                                primary = true,
                                icon = MintIcons.Check,
                                dark = dark,
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("transcription-accept-clean-button"),
                                onClick = {
                                    val chosenTodos = extractedTodoPreviews.filterIndexed { i, _ -> i in selectedTodos }
                                    onAcceptAiClean(cleanedText, chosenTodos)
                                },
                            )
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (dark) Color(0xFF1A2A22) else Color(0xFFF3F8F4))
                    .padding(14.dp),
            ) {
                Text(
                    text = status ?: if (loading) {
                        "读取中"
                    } else if (installed) {
                        "普通话转写在本机完成。"
                    } else {
                        "首次需下载约 42 MB 的普通话模型。之后可断网转写，录音留在本机。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
    }
}
