package io.github.z3275630ops.floatingnotes.ui.designsystem

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
    onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

@Composable
fun GlassSurface(
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val effective = tier.supportedOn(Build.VERSION.SDK_INT)
    val density = LocalDensity.current
    val radius = 28.dp
    val shape = RoundedCornerShape(radius)
    var position by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val effect = remember(effective, dark, viewport, density) {
        if (effective == GlassTier.Reduced || viewport == IntSize.Zero || Build.VERSION.SDK_INT < 31) {
            null
        } else with(density) {
            val blur = (if (dark) 24.dp else 20.dp).toPx()
            val blurred = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            if (effective == GlassTier.Enhanced && Build.VERSION.SDK_INT >= 33) {
                refract(viewport, radius.toPx(), 2.dp.toPx(), 10.dp.toPx(), blurred)
            } else {
                blurred.asComposeRenderEffect()
            }
        }
    }
    val tint = if (dark) Color(0xFF1A1E2A) else Color.White
    val alpha = when (effective) {
        GlassTier.Reduced -> 1f
        GlassTier.Standard -> if (dark) .84f else .78f
        GlassTier.Enhanced -> if (dark) .76f else .62f
    }
    Box(
        modifier
            .shadow(12.dp, shape, ambientColor = Color(0x14202431), spotColor = Color(0x14202431))
            .onGloballyPositioned { position = it.positionInRoot() }
            .clip(shape)
    ) {
        if (effective != GlassTier.Reduced) {
            Box(
                Modifier.matchParentSize().onSizeChanged { viewport = it }
                    .graphicsLayer { renderEffect = effect }
                    .drawWithContent {
                        val offset = backdrop.origin - position
                        translate(offset.x, offset.y) { drawLayer(backdrop.layer) }
                    }
            )
        }
        Box(
            Modifier.matchParentSize()
                .background(tint.copy(alpha = alpha))
                .background(Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = if (dark) .04f else .14f), Color.Transparent,
                )))
                .border(.75.dp, Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = if (dark) .20f else .65f),
                    Color.Transparent,
                    Color(0xFF6356D9).copy(alpha = .08f),
                )), shape)
        )
        content()
    }
}

@RequiresApi(33)
private fun refract(size: IntSize, radius: Float, amplitude: Float, band: Float, blurred: RenderEffect) =
    RuntimeShader("""
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
    """).run {
        setFloatUniform("resolution", size.width.toFloat(), size.height.toFloat())
        setFloatUniform("radius", radius)
        setFloatUniform("amplitude", amplitude)
        setFloatUniform("band", band)
        RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(this, "content"), blurred,
        ).asComposeRenderEffect()
    }
