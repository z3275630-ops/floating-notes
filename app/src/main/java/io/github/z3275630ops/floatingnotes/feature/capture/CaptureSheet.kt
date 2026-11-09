package io.github.z3275630ops.floatingnotes.feature.capture

import android.animation.ValueAnimator
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class CaptureMotion(private val scope: CoroutineScope, initiallyOpen: Boolean) {
    var visible by mutableStateOf(initiallyOpen)
        private set
    var progress by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set
    private var settleJob: Job? = null
    private var springVelocity = 0f

    fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    fun follow(value: Float) {
        progress = value.coerceIn(0f, 1f)
        springVelocity = 0f
    }

    fun settle(open: Boolean, initialVelocity: Float = springVelocity) {
        cancelSettle()
        val target = if (open) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            follow(target)
            visible = open
            return
        }
        visible = true
        settleJob = scope.launch {
            animate(progress, target, initialVelocity, spring(dampingRatio = .85f, stiffness = 500f)) { value, velocity ->
                progress = value.coerceIn(0f, 1f)
                springVelocity = velocity
            }
            springVelocity = 0f
            visible = open
        }
    }
}

@Composable
internal fun BoxScope.CaptureSheet(
    motion: CaptureMotion,
    open: Boolean,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onDragClose: (Float) -> Unit,
    onDragStart: () -> Unit,
    title: String = "快速记录",
    dismissLabel: String = "关闭",
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val dragClose by rememberUpdatedState(onDragClose)
    val dragStart by rememberUpdatedState(onDragStart)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    PredictiveBackHandler(enabled = open) { events ->
        motion.cancelSettle()
        val start = motion.progress
        try {
            events.collect { event ->
                if (ValueAnimator.areAnimatorsEnabled()) motion.follow(start * (1f - event.progress))
            }
            keyboard?.hide()
            focus.clearFocus()
            dismiss()
        } catch (cancelled: CancellationException) {
            motion.settle(true)
            throw cancelled
        }
    }

    if (!motion.visible) return
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val tier = if (imeVisible) GlassTier.Reduced else GlassTier.Standard
    var panelHeight by remember { mutableIntStateOf(0) }
    Box(
        Modifier.matchParentSize().graphicsLayer { alpha = motion.progress }
            .background(Color.Black.copy(alpha = .26f))
            .clickable { dismiss() }.clearAndSetSemantics { },
    )
    GlassSurface(
        backdrop, tier, dark,
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)
            .widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.82f)
            .onSizeChanged { panelHeight = it.height }
            .offset { IntOffset(0, ((1f - motion.progress) * (panelHeight + 32.dp.toPx())).roundToInt()) },
        sampleTopHeight = 112.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Box(
                Modifier.fillMaxWidth().height(48.dp).testTag("capture-handle").pointerInput(panelHeight) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            motion.cancelSettle()
                            dragStart()
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            val progressVelocity = -velocity / panelHeight.coerceAtLeast(1)
                            if (motion.progress < .65f || velocity > 1_000.dp.toPx()) {
                                dragClose(progressVelocity)
                            } else {
                                motion.settle(true, progressVelocity)
                            }
                        },
                        onDragCancel = { motion.settle(true) },
                    ) { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        motion.follow(motion.progress - amount / panelHeight.coerceAtLeast(1))
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp, 4.dp).background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), RoundedCornerShape(2.dp),
                ))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { dismiss() }) { Text(dismissLabel) }
            }
            content()
        }
    }
}
