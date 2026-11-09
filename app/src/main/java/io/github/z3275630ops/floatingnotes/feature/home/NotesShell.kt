package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.DebugSampleAction
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureMotion
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.*

private enum class Page(val label: String) {
    Record("记录"), Explore("探索"), Agent("Agent"), Library("笔记库"),
}

@Composable
internal fun NotesShell(labs: @Composable RowScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val backdrop = rememberGlassBackdrop()
    var page by rememberSaveable { mutableStateOf(Page.Record) }
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motion = remember { CaptureMotion(scope, open) }
    val draft = rememberTextFieldState()
    var samples by remember { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    fun close(velocity: Float? = null) {
        keyboard?.hide()
        focus.clearFocus()
        open = false
        if (velocity == null) motion.settle(false) else motion.settle(false, velocity)
    }

    Surface(color = scheme.background) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            Box(
                Modifier.fillMaxSize().glassSource(backdrop).background(ambient(dark))
                    .then(if (motion.visible) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                Crossfade(page, label = "home-page") { current ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                if (current == Page.Record) "浮记" else current.label,
                                style = MaterialTheme.typography.headlineLarge,
                            )
                        }
                        if (current == Page.Record) {
                            item {
                                Text(
                                    if (samples.isEmpty()) "记下一件今天想留住的事。" else "合成记录",
                                    color = scheme.onSurfaceVariant,
                                )
                                Row(content = labs)
                                DebugSampleAction { samples = it }
                            }
                            items(samples) { text ->
                                Card(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                ) {
                                    Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        } else {
                            item { Text("页面原型，功能开发中。", color = scheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            if (!motion.visible) {
                Column(
                    Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp)
                        .fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FloatingActionButton(
                        onClick = { open = true; motion.settle(true) },
                        modifier = Modifier.align(Alignment.End).testTag("new-note"),
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
                    GlassSurface(backdrop, GlassTier.Standard, dark, Modifier.fillMaxWidth()) {
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
                            val tabWidth = maxWidth / Page.entries.size
                            val position by animateDpAsState(
                                tabWidth * page.ordinal,
                                spring(dampingRatio = .85f, stiffness = 500f),
                                label = "tab-selection",
                            )
                            Box(
                                Modifier.offset { IntOffset(position.roundToPx(), 0) }.width(tabWidth).height(48.dp)
                                    .background(scheme.primary.copy(alpha = .13f), RoundedCornerShape(24.dp)),
                            )
                            Row(Modifier.fillMaxWidth().selectableGroup()) {
                                for (target in Page.entries) {
                                    Box(
                                        Modifier.weight(1f).heightIn(min = 48.dp)
                                            .testTag("tab-${target.name}")
                                            .selectable(page == target, role = Role.Tab) { page = target }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            target.label,
                                            color = if (page == target) scheme.primary else scheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            CaptureSheet(motion, open, backdrop, dark, { close() }, { close(it) }, { open = true }) {
                BasicTextField(
                    state = draft,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)
                        .testTag("capture-editor"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                )
                Text(
                    "输入面板原型 · 草稿保留在本次会话",
                    Modifier.padding(vertical = 12.dp), color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun ambient(dark: Boolean) = Brush.verticalGradient(if (dark) listOf(
    Color(0xFF28233F), Color(0xFF11131B), Color(0xFF19312E),
) else listOf(
    Color(0xFFE4DCFF), Color(0xFFF7F8FC), Color(0xFFD9ECFF), Color(0xFFDDF4ED),
))
