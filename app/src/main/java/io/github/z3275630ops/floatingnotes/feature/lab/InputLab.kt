package io.github.z3275630ops.floatingnotes.feature.lab

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputLab(onBack: () -> Unit) {
    val state = rememberTextFieldState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    val composing = state.composition != null

    Surface(color = scheme.background) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp),
        ) {
            TextButton(onClick = {
                keyboard?.hide()
                focus.clearFocus()
                onBack()
            }) { Text("返回工程样例") }

            Text("原生输入实验", style = MaterialTheme.typography.headlineSmall)
            Text("旋转保留正文与选区", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(
                    onClick = { state.undoState.undo() },
                    enabled = !composing && state.undoState.canUndo,
                ) { Text("撤销") }
                TextButton(
                    onClick = { state.undoState.redo() },
                    enabled = !composing && state.undoState.canRedo,
                ) { Text("重做") }
                TextButton(
                    enabled = !composing && state.text.isEmpty(),
                    onClick = {
                        state.edit { append("在纸上记下一点今天的想法。\n".repeat(2_500).take(30_000)) }
                    },
                ) { Text("填入三万字") }
            }
            val selection = state.selection
            Text(
                "${state.text.length} 字符 · 选区 ${selection.start}–${selection.end}" +
                    if (composing) " · 组词中" else "",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(12.dp))

            BasicTextField(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(scheme.surface, RoundedCornerShape(20.dp))
                    .testTag("input-lab-editor"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                lineLimits = TextFieldLineLimits.MultiLine(),
                decorator = TextFieldDecorator { inner ->
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        if (state.text.isEmpty()) Text("写下一个想法…", color = scheme.onSurfaceVariant)
                        inner()
                    }
                },
            )

            TextButton(onClick = { keyboard?.hide() }) { Text("收起键盘") }
        }
    }
}
