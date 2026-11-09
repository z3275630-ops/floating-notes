package io.github.z3275630ops.floatingnotes.feature.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.FloatingNotesTheme
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.feature.capture.*
import io.github.z3275630ops.floatingnotes.ui.designsystem.glassSource
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShareCaptureActivity : ComponentActivity() {
    private val model by viewModels<NotesViewModel>()
    private lateinit var captureId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureId = savedInstanceState?.getString("capture-id") ?: newId()
        enableEdgeToEdge()
        val payload = SharePayload.read(intent)
        setContent {
            FloatingNotesTheme {
                when (payload) {
                    is SharePayload.Rejected -> ShareMessage(payload.message, ::finish)
                    is SharePayload.Text -> SharePreview(captureId, payload, model, ::finish)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("capture-id", captureId)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        model.checkpointOnStop()
        super.onStop()
    }
}

@Composable
private fun SharePreview(
    captureId: String,
    payload: SharePayload.Text,
    model: NotesViewModel,
    finish: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val backdrop = rememberGlassBackdrop()
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(model.editor != null) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val motion = remember { CaptureMotion(scope, open) }

    LaunchedEffect(captureId) {
        when (model.openIncomingCapture(captureId, payload.body, payload.sourceUrl)) {
            IncomingCapture.Opened -> {
                open = true
                motion.settle(true)
            }
            IncomingCapture.AlreadySaved -> finish()
            IncomingCapture.Failed -> error = "无法打开收集草稿。请返回后重试。"
        }
    }
    LaunchedEffect(done, motion.visible) {
        if (done && !motion.visible) finish()
    }

    fun end(save: Boolean, velocity: Float? = null) {
        scope.launch(Dispatchers.Main.immediate) {
            if (model.finishEditor(save = save, discard = !save)) {
                open = false
                done = true
                if (velocity == null) motion.settle(false) else motion.settle(false, velocity)
            } else if (open) {
                motion.settle(true)
            }
        }
    }

    Surface(color = scheme.background) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier.fillMaxSize().glassSource(backdrop)
                    .background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .2f), scheme.background))),
            ) {
                if (error != null) {
                    ShareMessage(requireNotNull(error), finish)
                } else {
                    Text("跨应用收集", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                }
            }
            Box(Modifier.matchParentSize().imePadding()) {
                CaptureSheet(motion, open, backdrop, dark, { end(false) }, { end(false, it) }, { open = true },
                    title = "收集到浮记", dismissLabel = "取消") {
                    model.editor?.let { session ->
                        session.draft.sourceUrl?.let {
                            Text("来源链接 · $it", maxLines = 2, modifier = Modifier.padding(bottom = 8.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        NoteEditor(session, onSave = { end(true) }, onDiscard = { end(false) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareMessage(message: String, close: () -> Unit) {
    Surface {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = close) { Text("返回") }
            }
        }
    }
}
