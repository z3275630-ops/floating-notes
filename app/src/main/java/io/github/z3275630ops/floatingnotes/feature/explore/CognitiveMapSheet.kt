package io.github.z3275630ops.floatingnotes.feature.explore

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapCluster
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapEngine
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapMode
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapNode
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.MapEdgeKind
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSegmentedBar
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintLinkAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val LightClusterColors = listOf(
    Color(0xFF1F6B4F),
    Color(0xFF3E6FA8),
    Color(0xFF6B5BD6),
    Color(0xFFD98A1C),
    Color(0xFFC2603F),
    Color(0xFF2B8A88),
    Color(0xFF9353A6),
    Color(0xFF4F6D7A),
)

private val DarkClusterColors = listOf(
    Color(0xFF5FBF95),
    Color(0xFF74A3DB),
    Color(0xFF9B8FF0),
    Color(0xFFF2B44D),
    Color(0xFFE5896B),
    Color(0xFF56C2BF),
    Color(0xFFC48CE0),
    Color(0xFF8AA8B8),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CognitiveMapSheet(
    state: CognitiveMapUiState,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSelectMode: (CognitiveMapMode) -> Unit,
    onSelectMonth: (String?) -> Unit,
    onToggleRecluster: (Boolean) -> Unit,
    onRenameCluster: (clusterId: String, customName: String) -> Unit,
    onOpenNote: (String) -> Unit,
    onStartWalkFromNote: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val snapshot = state.snapshot

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedClusterId by remember { mutableStateOf<String?>(null) }
    var renamingCluster by remember { mutableStateOf<CognitiveMapCluster?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    LaunchedEffect(snapshot?.nodes, snapshot?.clusters) {
        if (snapshot == null) {
            selectedNodeId = null
            selectedClusterId = null
        } else {
            if (selectedNodeId != null && snapshot.nodes.none { it.noteId == selectedNodeId }) {
                selectedNodeId = null
            }
            if (selectedClusterId != null && snapshot.clusters.none { it.clusterId == selectedClusterId }) {
                selectedClusterId = null
            }
        }
    }

    val selectedNode: CognitiveMapNode? = remember(snapshot, selectedNodeId) {
        selectedNodeId?.let { id -> snapshot?.nodes?.firstOrNull { it.noteId == id } }
    }
    val selectedCluster: CognitiveMapCluster? = remember(snapshot, selectedClusterId, selectedNode) {
        val targetId = selectedClusterId ?: selectedNode?.clusterId
        targetId?.let { id -> snapshot?.clusters?.firstOrNull { it.clusterId == id } }
    }

    val clusterPalette = if (dark) DarkClusterColors else LightClusterColors
    val explicitEdgeColor = mintLinkAccent(dark).fg
    val sharedEdgeColor = scheme.onSurfaceVariant
    val labelTextColor = scheme.onSurface
    val labelPillColor = if (dark) Color(0xCC1C1C1E) else Color(0xDCF5F5F7)
    val labelBorderColor = mintDividerColor(dark)
    val selectedRingColor = scheme.primary

    val clusterLabelPaint = remember(density, labelTextColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 12.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = labelTextColor.toArgb()
        }
    }
    val nodeLabelPaint = remember(density, labelTextColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 10.5.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            color = labelTextColor.toArgb()
        }
    }

    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestZoom by rememberUpdatedState(zoom)
    val latestPan by rememberUpdatedState(pan)
    val latestSize by rememberUpdatedState(canvasSize)

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "认知地图",
        subtitle = "主题聚落与引用连接",
        leadingLabel = "关闭",
        onLeadingClick = onDismiss,
        maxHeightFraction = 0.94f,
        modifier = Modifier.testTag("cognitive-map-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MintSegmentedBar(
                options = listOf("主题全景 (2D)", "引用关系图"),
                selectedIndex = if (state.mode == CognitiveMapMode.TOPIC_MAP) 0 else 1,
                onSelect = { index ->
                    val nextMode = if (index == 0) CognitiveMapMode.TOPIC_MAP else CognitiveMapMode.RELATIONSHIP_GRAPH
                    if (nextMode != state.mode) onSelectMode(nextMode)
                },
                dark = dark,
                optionTags = listOf("map-mode-topic", "map-mode-relation"),
            )

            // Honest coverage notice + edge semantics legend
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(mintSecondaryFill(dark))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val coverageText = if (snapshot == null) {
                    "正在整理认知地图…"
                } else {
                    val samplingHint = if (snapshot.coveredCount < snapshot.eligibleCount && !snapshot.reclusteredForMonth) {
                        " · 按时间与关联度抽样"
                    } else {
                        ""
                    }
                    "已覆盖 ${snapshot.coveredCount} / ${snapshot.eligibleCount} 篇 · 算法近似，不代表客观距离$samplingHint"
                }
                Text(
                    text = coverageText,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("map-coverage-note"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EdgeLegendItem(
                        label = "主动引用",
                        color = explicitEdgeColor,
                        dashed = false,
                    )
                    if (state.mode == CognitiveMapMode.RELATIONSHIP_GRAPH) {
                        EdgeLegendItem(
                            label = "同标签关联",
                            color = sharedEdgeColor.copy(alpha = 0.72f),
                            dashed = true,
                        )
                    }
                    if (snapshot != null && snapshot.activeMonthFilter != null) {
                        Text(
                            text = "非选中月份降暗显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = mintTertiaryTextColor(dark),
                        )
                    }
                }
            }

            state.errorMessage?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(mintDestructiveColor(dark).copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = mintDestructiveColor(dark),
                    )
                }
            }

            // Single hardware-accelerated Canvas viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (dark) Color(0xFF17171A) else Color(0xFFFFFFFF),
                        RoundedCornerShape(20.dp),
                    )
                    .border(0.6.dp, mintDividerColor(dark), RoundedCornerShape(20.dp)),
            ) {
                when {
                    state.loading && snapshot == null -> {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MintActivityIndicator(color = scheme.primary)
                            Text(
                                text = "正在构建主题全景…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }

                    snapshot == null || snapshot.nodes.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp)
                                .testTag("cognitive-map-empty"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "还没有可绘制的笔记节点",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = "写下带有正文或标题的笔记后，这里会展示真实聚类与引用连接。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        val nodeById = remember(snapshot) { snapshot.nodes.associateBy { it.noteId } }
                        val clusterColorById = remember(snapshot, clusterPalette) {
                            snapshot.clusters.associate { cluster ->
                                cluster.clusterId to clusterPalette[cluster.colorIndex.mod(clusterPalette.size)]
                            }
                        }
                        val dashPath = remember(density) {
                            val dash = with(density) { 5.dp.toPx() }
                            val gap = with(density) { 4.dp.toPx() }
                            PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f)
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("cognitive-map-canvas")
                                .onSizeChanged { canvasSize = it }
                                .pointerInput(Unit) {
                                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                        val oldZoom = zoom
                                        val newZoom = (oldZoom * zoomChange).coerceIn(0.6f, 3.5f)
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val scaleFactor = if (oldZoom > 0f) newZoom / oldZoom else 1f
                                        val nextPan = if (scaleFactor != 1f) {
                                            (pan + center - centroid) * scaleFactor - (center - centroid) + panChange
                                        } else {
                                            pan + panChange
                                        }
                                        val maxPan = (minOf(size.width, size.height).toFloat() * newZoom * 0.65f)
                                            .coerceAtLeast(120f)
                                        zoom = newZoom
                                        pan = Offset(
                                            x = nextPan.x.coerceIn(-maxPan, maxPan),
                                            y = nextPan.y.coerceIn(-maxPan, maxPan),
                                        )
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures { tapOffset ->
                                        val activeSnapshot = latestSnapshot ?: return@detectTapGestures
                                        val w = latestSize.width.toFloat()
                                        val h = latestSize.height.toFloat()
                                        if (w <= 0f || h <= 0f) return@detectTapGestures
                                        val padPx = with(density) { 18.dp.toPx() }
                                        val worldSize = (minOf(w, h) - padPx * 2f).coerceAtLeast(1f)
                                        val nx = 0.5f + (tapOffset.x - w / 2f - latestPan.x) / (worldSize * latestZoom)
                                        val ny = 0.5f + (tapOffset.y - h / 2f - latestPan.y) / (worldSize * latestZoom)
                                        val hitRadius = (0.045f / latestZoom).coerceIn(0.018f, 0.065f)
                                        val hitNode = CognitiveMapEngine.hitTestNode(
                                            snapshot = activeSnapshot,
                                            normX = nx,
                                            normY = ny,
                                            radius = hitRadius,
                                        )
                                        if (hitNode != null) {
                                            selectedNodeId = hitNode.noteId
                                            selectedClusterId = hitNode.clusterId.takeIf { it != "sparse" }
                                        } else {
                                            val hitCluster = CognitiveMapEngine.hitTestCluster(activeSnapshot, nx, ny)
                                            selectedNodeId = null
                                            selectedClusterId = hitCluster?.clusterId
                                        }
                                    }
                                },
                        ) {
                            clipRect {
                                val padPx = 18.dp.toPx()
                                val worldSize = (minOf(size.width, size.height) - padPx * 2f).coerceAtLeast(1f)
                                val originX = size.width / 2f
                                val originY = size.height / 2f

                                fun toScreen(nx: Float, ny: Float): Offset = Offset(
                                    x = originX + (nx - 0.5f) * worldSize * zoom + pan.x,
                                    y = originY + (ny - 0.5f) * worldSize * zoom + pan.y,
                                )

                                // Subtle Apple-style concentric reference rings
                                val centerScreen = toScreen(0.5f, 0.5f)
                                val ringColor = (if (dark) Color.White else Color.Black).copy(alpha = 0.04f)
                                for (fraction in listOf(0.22f, 0.42f)) {
                                    drawCircle(
                                        color = ringColor,
                                        radius = worldSize * zoom * fraction,
                                        center = centerScreen,
                                        style = Stroke(width = 0.8.dp.toPx()),
                                    )
                                }

                                // 1. Soft topic cluster halos
                                for (cluster in snapshot.clusters) {
                                    val cPos = toScreen(cluster.centerX, cluster.centerY)
                                    val cRadiusPx = (cluster.radius * worldSize * zoom * 1.18f).coerceAtLeast(24f)
                                    if (cPos.x + cRadiusPx < -40f || cPos.x - cRadiusPx > size.width + 40f ||
                                        cPos.y + cRadiusPx < -40f || cPos.y - cRadiusPx > size.height + 40f
                                    ) {
                                        continue
                                    }
                                    val baseColor = clusterColorById[cluster.clusterId] ?: selectedRingColor
                                    val isClusterSelected = selectedCluster?.clusterId == cluster.clusterId
                                    val centerAlpha = if (isClusterSelected) {
                                        if (dark) 0.24f else 0.17f
                                    } else {
                                        if (dark) 0.14f else 0.10f
                                    }
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                baseColor.copy(alpha = centerAlpha),
                                                baseColor.copy(alpha = centerAlpha * 0.35f),
                                                Color.Transparent,
                                            ),
                                            center = cPos,
                                            radius = cRadiusPx,
                                        ),
                                        radius = cRadiusPx,
                                        center = cPos,
                                    )
                                    if (isClusterSelected) {
                                        drawCircle(
                                            color = baseColor.copy(alpha = 0.55f),
                                            radius = cluster.radius * worldSize * zoom,
                                            center = cPos,
                                            style = Stroke(width = 1.4.dp.toPx()),
                                        )
                                    }
                                }

                                // 2. Edges: solid EXPLICIT_LINK vs faint dashed SHARED_TAG
                                val explicitStroke = 1.6.dp.toPx()
                                val explicitSelectedStroke = 2.4.dp.toPx()
                                val sharedStroke = 1.0.dp.toPx()
                                val arrowLength = 6.dp.toPx()

                                for (edge in snapshot.edges) {
                                    val source = nodeById[edge.sourceNoteId] ?: continue
                                    val target = nodeById[edge.targetNoteId] ?: continue
                                    val start = toScreen(source.x, source.y)
                                    val end = toScreen(target.x, target.y)
                                    if ((start.x < -40f && end.x < -40f) ||
                                        (start.x > size.width + 40f && end.x > size.width + 40f) ||
                                        (start.y < -40f && end.y < -40f) ||
                                        (start.y > size.height + 40f && end.y > size.height + 40f)
                                    ) {
                                        continue
                                    }
                                    val touchesSelected = selectedNodeId != null &&
                                        (edge.sourceNoteId == selectedNodeId || edge.targetNoteId == selectedNodeId)
                                    val bothDimmed = !source.isHighlighted && !target.isHighlighted
                                    val dimFactor = if (bothDimmed) 0.32f else 1f

                                    when (edge.kind) {
                                        MapEdgeKind.SHARED_TAG -> {
                                            val alpha = (if (touchesSelected) 0.52f else 0.20f) * dimFactor
                                            drawLine(
                                                color = sharedEdgeColor.copy(alpha = alpha),
                                                start = start,
                                                end = end,
                                                strokeWidth = sharedStroke,
                                                pathEffect = dashPath,
                                                cap = StrokeCap.Round,
                                            )
                                        }

                                        MapEdgeKind.EXPLICIT_LINK -> {
                                            val alpha = (if (touchesSelected) 0.90f else 0.50f) * dimFactor
                                            val color = explicitEdgeColor.copy(alpha = alpha)
                                            drawLine(
                                                color = color,
                                                start = start,
                                                end = end,
                                                strokeWidth = if (touchesSelected) explicitSelectedStroke else explicitStroke,
                                                cap = StrokeCap.Round,
                                            )
                                            val dx = end.x - start.x
                                            val dy = end.y - start.y
                                            if (dx * dx + dy * dy > arrowLength * arrowLength * 4f) {
                                                val angle = atan2(dy, dx)
                                                val tipPull = 7.dp.toPx()
                                                val tip = Offset(
                                                    x = end.x - tipPull * cos(angle),
                                                    y = end.y - tipPull * sin(angle),
                                                )
                                                val wing1 = Offset(
                                                    x = tip.x - arrowLength * cos(angle - 0.48f),
                                                    y = tip.y - arrowLength * sin(angle - 0.48f),
                                                )
                                                val wing2 = Offset(
                                                    x = tip.x - arrowLength * cos(angle + 0.48f),
                                                    y = tip.y - arrowLength * sin(angle + 0.48f),
                                                )
                                                drawLine(color = color, start = tip, end = wing1, strokeWidth = explicitStroke, cap = StrokeCap.Round)
                                                drawLine(color = color, start = tip, end = wing2, strokeWidth = explicitStroke, cap = StrokeCap.Round)
                                            }
                                        }
                                    }
                                }

                                // 3. Restrained node dots (non-highlighted months dimmed to alpha = 0.25f)
                                val zoomScale = (0.85f + 0.15f * zoom.coerceAtMost(2.2f))
                                for (node in snapshot.nodes) {
                                    val pos = toScreen(node.x, node.y)
                                    if (pos.x < -30f || pos.x > size.width + 30f ||
                                        pos.y < -30f || pos.y > size.height + 30f
                                    ) {
                                        continue
                                    }
                                    val isNodeSelected = node.noteId == selectedNodeId
                                    val dotColor = clusterColorById[node.clusterId] ?: selectedRingColor
                                    val alpha = when {
                                        isNodeSelected -> 1f
                                        !node.isHighlighted -> 0.25f
                                        else -> 0.90f
                                    }
                                    val radiusPx = ((4.0f + minOf(node.degree, 6) * 0.65f).dp.toPx() * zoomScale)
                                        .let { if (isNodeSelected) it * 1.35f else it }

                                    if (isNodeSelected) {
                                        drawCircle(
                                            color = dotColor.copy(alpha = 0.24f),
                                            radius = radiusPx + 6.dp.toPx(),
                                            center = pos,
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = radiusPx + 2.dp.toPx(),
                                            center = pos,
                                            style = Stroke(width = 1.6.dp.toPx()),
                                        )
                                    }
                                    drawCircle(
                                        color = dotColor.copy(alpha = alpha),
                                        radius = radiusPx,
                                        center = pos,
                                    )
                                }

                                // 4. Viewport-clipped nativeCanvas cluster & zoomed node labels (API 29 safe)
                                val labelPadH = 8.dp.toPx()
                                val labelHeight = 20.dp.toPx()
                                val labelRadius = 10.dp.toPx()
                                val metrics = clusterLabelPaint.fontMetrics
                                val baselineOffset = -(metrics.ascent + metrics.descent) / 2f

                                for (cluster in snapshot.clusters) {
                                    val anchor = toScreen(cluster.centerX, cluster.centerY)
                                    val labelY = (anchor.y - cluster.radius * worldSize * zoom * 0.72f)
                                        .coerceIn(18.dp.toPx(), size.height - 18.dp.toPx())
                                    if (anchor.x !in -80f..(size.width + 80f) || labelY !in -40f..(size.height + 40f)) {
                                        continue
                                    }
                                    val label = safeUtf16Slice(cluster.displayLabel, 0, 14)
                                    val textWidth = clusterLabelPaint.measureText(label)
                                    val pillWidth = textWidth + labelPadH * 2f
                                    val pillLeft = anchor.x - pillWidth / 2f
                                    val pillTop = labelY - labelHeight / 2f
                                    val isClusterSelected = selectedCluster?.clusterId == cluster.clusterId

                                    drawRoundRect(
                                        color = labelPillColor,
                                        topLeft = Offset(pillLeft, pillTop),
                                        size = Size(pillWidth, labelHeight),
                                        cornerRadius = CornerRadius(labelRadius, labelRadius),
                                    )
                                    drawRoundRect(
                                        color = if (isClusterSelected) {
                                            (clusterColorById[cluster.clusterId] ?: selectedRingColor).copy(alpha = 0.8f)
                                        } else {
                                            labelBorderColor
                                        },
                                        topLeft = Offset(pillLeft, pillTop),
                                        size = Size(pillWidth, labelHeight),
                                        cornerRadius = CornerRadius(labelRadius, labelRadius),
                                        style = Stroke(width = if (isClusterSelected) 1.2.dp.toPx() else 0.6.dp.toPx()),
                                    )
                                    drawContext.canvas.nativeCanvas.drawText(
                                        label,
                                        anchor.x,
                                        labelY + baselineOffset,
                                        clusterLabelPaint,
                                    )
                                }

                                // Draw selected node label, or nearby highlighted node labels when zoomed in >= 1.7f
                                val labeledNodes = when {
                                    selectedNode != null -> listOf(selectedNode)
                                    zoom >= 1.7f -> snapshot.nodes.filter { it.isHighlighted }.take(18)
                                    else -> emptyList()
                                }
                                for (node in labeledNodes) {
                                    val pos = toScreen(node.x, node.y)
                                    if (pos.x !in 0f..size.width || pos.y !in 0f..size.height) continue
                                    val text = safeUtf16Slice(node.title, 0, 14)
                                    drawContext.canvas.nativeCanvas.drawText(
                                        text,
                                        pos.x,
                                        pos.y + 16.dp.toPx(),
                                        nodeLabelPaint,
                                    )
                                }
                            }
                        }

                        if (snapshot.isSparse) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(labelPillColor)
                                    .border(0.6.dp, mintDividerColor(dark), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("map-sparse-hint"),
                            ) {
                                Text(
                                    text = "笔记尚少（${snapshot.eligibleCount} 篇），暂不生成主题聚落，继续积累后将自动聚类",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (zoom != 1f || pan != Offset.Zero) {
                            MintChip(
                                label = "重置视角",
                                dark = dark,
                                style = MintChipStyle.SmartFolder,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .testTag("map-reset-view"),
                                onClick = {
                                    zoom = 1f
                                    pan = Offset.Zero
                                },
                            )
                        }
                    }
                }
            }

            // Selected cluster or node detail Apple grouped card
            AnimatedVisibility(visible = selectedCluster != null || selectedNode != null) {
                val cluster = selectedCluster
                val node = selectedNode
                MintGroupedSection(
                    dark = dark,
                    containerColor = mintCardSurface(dark),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 225.dp)
                        .testTag("map-selection-card"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (cluster != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cluster.displayLabel,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("map-selected-cluster-title"),
                                    )
                                    Text(
                                        text = "算法原始标签：${cluster.machineLabel} · ${cluster.noteIds.size} 篇",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("map-selected-cluster-machine"),
                                    )
                                }
                                MintChip(
                                    label = "重命名主题",
                                    leadingIcon = MintIcons.Compose,
                                    dark = dark,
                                    modifier = Modifier.testTag("map-rename-cluster-button"),
                                    onClick = {
                                        renameDraft = cluster.displayLabel
                                        renamingCluster = cluster
                                    },
                                )
                            }
                        }

                        if (node != null) {
                            if (cluster != null) MintGroupedDivider(dark = dark, startIndent = 0.dp)
                            Text(
                                text = node.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("map-selected-node-title"),
                            )
                            Text(
                                text = "${node.yearMonth} · ${node.degree} 条连接 · ${node.excerpt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (node.tagNames.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    node.tagNames.take(5).forEach { tagName ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(mintSecondaryFill(dark))
                                                .padding(horizontal = 7.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "#$tagName",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant,
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
                                    label = "打开原文",
                                    primary = true,
                                    dark = dark,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("map-open-note"),
                                    onClick = { onOpenNote(node.noteId) },
                                )
                                MintPillButton(
                                    label = "从这条开始漫步",
                                    icon = MintIcons.Explore,
                                    primary = false,
                                    dark = dark,
                                    modifier = Modifier
                                        .weight(1.25f)
                                        .testTag("map-start-walk"),
                                    onClick = { onStartWalkFromNote(node.noteId) },
                                )
                            }
                        } else if (cluster != null && snapshot != null) {
                            val previewNodes = remember(cluster, snapshot) {
                                val byId = snapshot.nodes.associateBy { it.noteId }
                                cluster.noteIds.mapNotNull { byId[it] }.take(4)
                            }
                            if (previewNodes.isNotEmpty()) {
                                MintGroupedDivider(dark = dark, startIndent = 0.dp)
                                previewNodes.forEachIndexed { idx, item ->
                                    if (idx > 0) MintGroupedDivider(dark = dark, startIndent = 0.dp)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Button,
                                                onClick = { selectedNodeId = item.noteId },
                                            )
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = scheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = item.excerpt,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = scheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "查看",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = scheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Fixed bottom timeline month pills & recluster toggle
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val months = snapshot?.availableMonths.orEmpty()
                    MintChip(
                        label = "全部",
                        dark = dark,
                        selected = state.activeMonthFilter == null,
                        style = if (state.activeMonthFilter == null) MintChipStyle.Selected else MintChipStyle.Standard,
                        modifier = Modifier.testTag("map-month-all"),
                        onClick = { onSelectMonth(null) },
                    )
                    months.forEach { month ->
                        val active = state.activeMonthFilter == month
                        MintChip(
                            label = month,
                            dark = dark,
                            selected = active,
                            style = if (active) MintChipStyle.Selected else MintChipStyle.Standard,
                            modifier = Modifier.testTag("map-month-$month"),
                            onClick = { onSelectMonth(month) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MintChip(
                        label = "按当月重新聚类",
                        dark = dark,
                        selected = state.reclusterForMonth,
                        style = if (state.reclusterForMonth) MintChipStyle.Selected else MintChipStyle.Standard,
                        modifier = Modifier.testTag("map-recluster-toggle"),
                        onClick = { onToggleRecluster(!state.reclusterForMonth) },
                    )
                    Text(
                        text = if (state.activeMonthFilter == null && state.reclusterForMonth) {
                            "选择具体月份后生效"
                        } else {
                            "双指缩放 · 点击节点查看"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = mintTertiaryTextColor(dark),
                    )
                }
            }
        }
    }

    renamingCluster?.let { cluster ->
        MintAlertDialog(
            onDismissRequest = { renamingCluster = null },
            dark = dark,
            backdrop = backdrop,
            title = "重命名主题",
            subtitle = "算法原始标签：${cluster.machineLabel}",
            confirmLabel = "保存",
            onConfirmAction = {
                onRenameCluster(cluster.clusterId, renameDraft)
                renamingCluster = null
            },
        ) {
            MintTextField(
                value = renameDraft,
                onValueChange = { renameDraft = it },
                placeholder = "输入主题名称（留空恢复原始标签）",
                dark = dark,
                tag = "map-rename-cluster-input",
            )
        }
    }
}

@Composable
private fun EdgeLegendItem(
    label: String,
    color: Color,
    dashed: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 8.dp)) {
            val y = size.height / 2f
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.6.dp.toPx(),
                pathEffect = if (dashed) {
                    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)
                } else {
                    null
                },
                cap = StrokeCap.Round,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
