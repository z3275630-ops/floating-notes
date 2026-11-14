package io.github.z3275630ops.floatingnotes.feature.capture

import android.animation.ValueAnimator
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    fullScreen: Boolean = false,
    autoFocus: Boolean = false,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val dragClose by rememberUpdatedState(onDragClose)
    val dragStart by rememberUpdatedState(onDragStart)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val contentFocus = remember { FocusRequester() }

    fun dismissCleanly(action: () -> Unit) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        action()
    }

    LaunchedEffect(open, autoFocus) {
        if (open && autoFocus) {
            withFrameNanos { }
            runCatching {
                contentFocus.requestFocus()
                focus.moveFocus(FocusDirection.Next)
                keyboard?.show()
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        PredictiveBackHandler(enabled = open) { events ->
            motion.cancelSettle()
            val start = motion.progress
            try {
                events.collect { event ->
                    if (ValueAnimator.areAnimatorsEnabled()) motion.follow(start * (1f - event.progress))
                }
                dismissCleanly { dismiss() }
            } catch (cancelled: CancellationException) {
                motion.settle(true)
                throw cancelled
            }
        }
    } else {
        BackHandler(enabled = open) {
            motion.cancelSettle()
            dismissCleanly { dismiss() }
        }
    }

    if (!motion.visible) return
    val scheme = MaterialTheme.colorScheme
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val tier = if (imeVisible || fullScreen) GlassTier.Reduced else GlassTier.Enhanced
    var panelHeight by remember { mutableIntStateOf(0) }
    val sheetShape = RoundedCornerShape(
        topStart = if (fullScreen) 0.dp else 28.dp,
        topEnd = if (fullScreen) 0.dp else 28.dp,
        bottomStart = if (fullScreen || imeVisible) 0.dp else 28.dp,
        bottomEnd = if (fullScreen || imeVisible) 0.dp else 28.dp,
    )
    val horizontalPad = if (fullScreen) 0.dp else if (imeVisible) 2.dp else 6.dp
    val bottomPad = if (fullScreen || imeVisible) 0.dp else 10.dp
    val heightFraction = if (fullScreen) 1f else if (imeVisible) .94f else .84f

    if (!fullScreen) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = motion.progress }
                .background(Color.Black.copy(alpha = .28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissCleanly { dismiss() } },
                )
                .clearAndSetSemantics { },
        )
    }
    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = horizontalPad, end = horizontalPad, bottom = bottomPad)
            .widthIn(max = if (fullScreen) 900.dp else 640.dp)
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .onSizeChanged { panelHeight = it.height }
            .offset { IntOffset(0, ((1f - motion.progress) * (panelHeight + 32.dp.toPx())).roundToInt()) },
        sampleTopHeight = if (fullScreen) null else 96.dp,
        shapeRadius = if (fullScreen) 0.dp else 28.dp,
        elevatedModal = true,
        shape = sheetShape,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (fullScreen) 12.dp else 10.dp)) {
            if (!fullScreen) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("capture-handle")
                        .pointerInput(panelHeight) {
                            val tracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = {
                                    tracker.resetTracking()
                                    motion.cancelSettle()
                                    keyboard?.hide()
                                    dragStart()
                                },
                                onDragEnd = {
                                    val velocity = tracker.calculateVelocity().y
                                    val progressVelocity = -velocity / panelHeight.coerceAtLeast(1)
                                    if (motion.progress < .65f || velocity > 1_000.dp.toPx()) {
                                        dismissCleanly { dragClose(progressVelocity) }
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
                    Box(
                        Modifier
                            .size(40.dp, 4.dp)
                            .background(scheme.onSurfaceVariant.copy(alpha = .32f), RoundedCornerShape(2.dp)),
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (fullScreen) {
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { dismissCleanly { dismiss() } },
                            )
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MintIcons.Back,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = dismissLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.primary,
                        )
                    }
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    headerActions?.invoke(this)
                } else {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    headerActions?.invoke(this)
                    MintPillButton(
                        label = dismissLabel,
                        onClick = { dismissCleanly { dismiss() } },
                        dark = dark,
                    )
                }
            }
            Spacer(Modifier.height(if (fullScreen) 6.dp else 4.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(contentFocus)
                    .focusTarget(),
                content = content,
            )
        }
    }
}
