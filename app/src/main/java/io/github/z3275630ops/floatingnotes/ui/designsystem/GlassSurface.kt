package io.github.z3275630ops.floatingnotes.ui.designsystem

import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

enum class GlassTier(val label: String) {
    Reduced("清晰"), Standard("标准"), Enhanced("增强");

    fun supportedOn(api: Int) = when {
        api < 31 -> Reduced
        api < 33 && this == Enhanced -> Standard
        else -> this
    }
}

@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Record the scene beneath glass. Keep all GlassSurface instances outside this subtree. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    if (Build.VERSION.SDK_INT < 31) {
        this
    } else {
        onGloballyPositioned { backdrop.origin = it.positionInRoot() }
            .drawWithContent {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            }
    }

@Composable
fun GlassSurface(
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
    modifier: Modifier = Modifier,
    sampleTopHeight: Dp? = null,
    shapeRadius: Dp = 28.dp,
    elevatedModal: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(shapeRadius),
    showBorder: Boolean = true,
    showShadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val effective = tier.supportedOn(Build.VERSION.SDK_INT)
    val density = LocalDensity.current
    val radius = shapeRadius
    val blurPx = with(density) {
        when (effective) {
            GlassTier.Enhanced -> 16.dp
            else -> 22.dp
        }.toPx()
    }
    val radiusPx = with(density) { radius.toPx() }
    val amplitudePx = with(density) { 4.dp.toPx() }
    val bandPx = with(density) { 15.dp.toPx() }
    val effectHolder: GlassEffectHolder? = remember(effective) {
        if (effective == GlassTier.Reduced || Build.VERSION.SDK_INT < 31) {
            null
        } else {
            createApi31GlassEffectHolder()
        }
    }
    var position by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val effect: ComposeRenderEffect? = remember(
        effectHolder,
        effective,
        viewport,
        blurPx,
        radiusPx,
        amplitudePx,
        bandPx,
    ) {
        effectHolder?.resolve(
            effective = effective,
            viewport = viewport,
            blurPx = blurPx,
            radiusPx = radiusPx,
            amplitudePx = amplitudePx,
            bandPx = bandPx,
        )
    }
    val tint = if (dark) {
        if (elevatedModal) Color(0xFF252421) else Color(0xFF1C1C1A)
    } else {
        if (elevatedModal) Color(0xFFFFFFFF) else Color(0xFFFCFBF8)
    }
    val alpha = when (effective) {
        GlassTier.Reduced -> if (elevatedModal) 1f else .97f
        GlassTier.Standard -> if (elevatedModal) (if (dark) .94f else .94f) else (if (dark) .86f else .84f)
        GlassTier.Enhanced -> if (elevatedModal) (if (dark) .92f else .90f) else (if (dark) .82f else .68f)
    }
    val elevation = if (!showShadow) 0.dp else if (elevatedModal) 8.dp else 2.dp
    val shadowTint = if (dark) Color(0x40000000) else Color(0x0B1C1B19)
    Box(
        modifier
            .then(
                if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = shadowTint, spotColor = shadowTint)
                else Modifier
            )
            .onGloballyPositioned { position = it.positionInRoot() }
            .clip(shape)
    ) {
        if (effective != GlassTier.Reduced) {
            val sample = if (sampleTopHeight == null) Modifier.matchParentSize()
                else Modifier.fillMaxWidth().height(sampleTopHeight)
            Box(
                sample.onSizeChanged { viewport = it }
                    .graphicsLayer { renderEffect = effect }
                    .drawWithContent {
                        val offset = backdrop.origin - position
                        translate(offset.x, offset.y) { drawLayer(backdrop.layer) }
                    }
            )
        }
        val surface = if (sampleTopHeight == null) Modifier.background(tint.copy(alpha = alpha))
        else Modifier.background(Brush.verticalGradient(
            colors = listOf(tint.copy(alpha = if (effective == GlassTier.Reduced) 1f else alpha), tint),
            endY = with(density) { sampleTopHeight.toPx() },
        ))
        val specularTopAlpha = if (effective == GlassTier.Reduced) 0f else (if (dark) .05f else .18f)
        val specularBottomAlpha = if (effective == GlassTier.Reduced) 0f else (if (dark) .06f else .10f)
        val borderModifier = if (!showBorder) {
            Modifier
        } else if (effective == GlassTier.Reduced) {
            Modifier.border(
                width = 0.6.dp,
                color = mintDividerColor(dark),
                shape = shape,
            )
        } else {
            Modifier.border(
                0.8.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) .22f else .86f),
                        Color(0xFFE7E4DC).copy(alpha = if (dark) .06f else .28f),
                        Color(0xFF1F6B4F).copy(alpha = if (dark) .22f else .16f),
                    ),
                ),
                shape,
            )
        }
        Box(
            Modifier.matchParentSize().then(surface)
                .then(
                    if (specularTopAlpha > 0f || specularBottomAlpha > 0f) {
                        Modifier.background(Brush.verticalGradient(listOf(
                            Color.White.copy(alpha = specularTopAlpha),
                            Color.Transparent,
                            Color(0xFFE4F0EA).copy(alpha = specularBottomAlpha),
                        )))
                    } else Modifier
                )
                .then(borderModifier)
        )
        content()
    }
}

private interface GlassEffectHolder {
    fun resolve(
        effective: GlassTier,
        viewport: IntSize,
        blurPx: Float,
        radiusPx: Float,
        amplitudePx: Float,
        bandPx: Float,
    ): ComposeRenderEffect?
}

@RequiresApi(31)
private fun createApi31GlassEffectHolder(): GlassEffectHolder = Api31GlassEffectHolder()

@RequiresApi(31)
private interface Api33RefractBridge {
    fun chainRefract(
        size: IntSize,
        radius: Float,
        amplitude: Float,
        band: Float,
        blurred: android.graphics.RenderEffect,
    ): ComposeRenderEffect
}

@RequiresApi(33)
private fun createApi33RefractBridge(): Api33RefractBridge = Api33RefractHelper()

@RequiresApi(31)
private class Api31GlassEffectHolder : GlassEffectHolder {
    private var cachedBlurPx: Float = -1f
    private var cachedBlur: android.graphics.RenderEffect? = null
    private var refractHelper: Api33RefractBridge? = null

    override fun resolve(
        effective: GlassTier,
        viewport: IntSize,
        blurPx: Float,
        radiusPx: Float,
        amplitudePx: Float,
        bandPx: Float,
    ): ComposeRenderEffect? {
        if (effective == GlassTier.Reduced || viewport == IntSize.Zero) return null
        val blur = cachedBlur?.takeIf { cachedBlurPx == blurPx }
            ?: android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP).also {
                cachedBlurPx = blurPx
                cachedBlur = it
            }
        return if (effective == GlassTier.Enhanced && Build.VERSION.SDK_INT >= 33) {
            val helper = refractHelper ?: createApi33RefractBridge().also { refractHelper = it }
            helper.chainRefract(viewport, radiusPx, amplitudePx, bandPx, blur)
        } else {
            blur.asComposeRenderEffect()
        }
    }
}

@RequiresApi(33)
private class Api33RefractHelper : Api33RefractBridge {
    private val shader = android.graphics.RuntimeShader(REFRACT_SHADER_SOURCE)

    override fun chainRefract(
        size: IntSize,
        radius: Float,
        amplitude: Float,
        band: Float,
        blurred: android.graphics.RenderEffect,
    ): ComposeRenderEffect {
        shader.setFloatUniform("resolution", size.width.toFloat(), size.height.toFloat())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("amplitude", amplitude)
        shader.setFloatUniform("band", band)
        return android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
            blurred,
        ).asComposeRenderEffect()
    }
}

private const val REFRACT_SHADER_SOURCE = """
    uniform shader content;
    uniform float2 resolution;
    uniform float radius;
    uniform float amplitude;
    uniform float band;

    half4 main(float2 p) {
        float2 halfSize = resolution * 0.5;
        float2 local = p - halfSize;
        float2 q = abs(local) - halfSize + radius;
        float distanceToEdge = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        float edge = 1.0 - smoothstep(0.0, band, -distanceToEdge);
        float2 direction = local / max(length(local), 0.001);
        float2 sampleAt = clamp(p - direction * amplitude * edge, float2(0.0), resolution - 0.5);
        return content.eval(sampleAt);
    }
"""
