package io.github.z3275630ops.floatingnotes.feature.capture

import android.content.Context
import android.util.AtomicFile
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.LocalAttachment
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
        val json = JSONObject(result.readFully().decodeToString())
        return Transcript(json.getString("raw"), json.getString("formatted"))
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
    insert: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val transcription = remember(attachment.id) { TranscriptionStore(context, store.original(attachment)) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var installed by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    var original by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
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
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnClickOutside = job == null),
        title = { Text("离线转写") },
        text = {
            Column {
                if (text != null) {
                    TextButton(onClick = { original = !original }) { Text(if (original) "查看整理版" else "查看原文") }
                    Text(text, Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
                } else {
                    Text(status ?: if (loading) "读取中" else if (installed) {
                        "普通话转写在本机完成。"
                    } else {
                        "首次需下载约 42 MB 的普通话模型。之后可断网转写，录音留在本机。"
                    })
                }
            }
        },
        confirmButton = {
            if (text != null) TextButton(onClick = { insert(text); dismiss() }) { Text("插入正文") }
            else TextButton(enabled = !loading && job == null, onClick = {
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
            }) { Text(if (job != null) "转写中" else if (status != null) "重试" else if (installed) "开始转写" else "下载并转写") }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(if (job == null) "关闭" else "取消") }
        },
    )
}
