package com.thealgothrim.dwellplay.ui

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalScreen
import com.thealgothrim.dwellplay.ui.theme.Palette
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The navigation glass from WearWise and Dueline, on Android: the content behind the dock is
 * bent along the dock's rim the way thick glass bends it, then softened and toned down.
 *
 * The web apps do it with an SVG displacement map; here a GPU shader computes the same bevel (the
 * distance to the rounded edge, turned into inward normals) and moves each sample inward, hardest
 * at the rim. The tuning is WearWise's: a 15dp bevel, up to 12dp of bend, a 5dp blur, saturation
 * 1.25, brightness 0.82, the app's own ink as the tint, a faint rim light and a crimson inner
 * glow. No white glare.
 *
 * Android 13+ runs the shader. Older phones and Android Automotive cars get the same surface without
 * the bend, nearly opaque, so text over it always reads.
 */

/** The browsing content, recorded each frame so the glass above it can redraw it bent. */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
  internal var coordinates: LayoutCoordinates? = null
}

@Composable
fun rememberBackdrop(): Backdrop {
  val layer = rememberGraphicsLayer()
  return remember(layer) { Backdrop(layer) }
}

/** Marks the content the glass bends. Put it on an opaque container below the floating chrome. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier =
    onPlaced { backdrop.coordinates = it }
        .drawWithContent {
          backdrop.layer.record { this@drawWithContent.drawContent() }
          drawLayer(backdrop.layer)
        }

/** Room the floating navigation takes over the content: the car rail at the start, the dock below. */
@Immutable data class ChromeInsets(val start: Dp = 0.dp, val bottom: Dp = 0.dp)

val LocalChromeInsets = compositionLocalOf { ChromeInsets() }

/** Where browsing content starts: the gutter, plus the car rail floating over the start edge. */
@Composable
@ReadOnlyComposable
fun gutterStart(): Dp = LocalDims.current.gutter + LocalChromeInsets.current.start

/** Horizontal gutters for browsing content, clear of the floating rail. */
@Composable
fun Modifier.gutters(): Modifier = padding(start = gutterStart(), end = LocalDims.current.gutter)

/**
 * A floating glass surface. `corner` larger than half the height makes a pill. The content sits on
 * top; the glass is drawn in a separate layer underneath so the shadows never tint it.
 */
@Composable
fun Glass(
    backdrop: Backdrop?,
    corner: Dp,
    modifier: Modifier = Modifier,
    /** Tint strength where nothing can be bent, for example over a video surface. */
    opacity: Float = 0.92f,
    content: @Composable BoxScope.() -> Unit,
) {
  val shape = RoundedCornerShape(corner)
  Box(modifier.dropShadow(shape, Shadow(radius = 40.dp, color = Color.Black.copy(alpha = 0.8f), spread = (-10).dp, offset = DpOffset(0.dp, 14.dp)))) {
    Box(
        Modifier.matchParentSize()
            .glassFill(backdrop, corner, opacity)
            .innerShadow(shape, Shadow(radius = 22.dp, color = Palette.Accent.copy(alpha = 0.18f)))
            .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.10f), offset = DpOffset(0.dp, 1.dp)))
            .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.04f), offset = DpOffset(0.dp, (-1).dp)))
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
    )
    content()
  }
}

private class Placement {
  var coordinates: LayoutCoordinates? = null
}

@Composable
private fun Modifier.glassFill(backdrop: Backdrop?, corner: Dp, opacity: Float): Modifier {
  val bends = backdrop != null && Build.VERSION.SDK_INT >= 33 && LocalScreen.current != Screen.AUTOMOTIVE
  val layer = rememberGraphicsLayer()
  val lens = remember(bends) { if (bends && Build.VERSION.SDK_INT >= 33) Lens() else null }
  val placement = remember { Placement() }
  return onPlaced { placement.coordinates = it }
      .drawBehind {
        val radius = min(corner.toPx(), size.minDimension / 2)
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
        val source = backdrop?.coordinates
        val mine = placement.coordinates
        var bent = false
        if (lens != null && backdrop != null && source != null && mine != null && source.isAttached && mine.isAttached) {
          val at = source.localPositionOf(mine, Offset.Zero)
          // Record a margin around the glass so the blur and the bend never sample past the edge.
          val margin = 24.dp.toPx()
          layer.renderEffect = lens.effect(size, margin, radius, 15.dp.toPx(), 12.dp.toPx(), 5.dp.toPx())
          layer.record(IntSize((size.width + margin * 2).roundToInt(), (size.height + margin * 2).roundToInt())) {
            translate(margin - at.x, margin - at.y) { drawLayer(backdrop.layer) }
          }
          clipPath(outline) {
            drawRect(Palette.Canvas)
            translate(-margin, -margin) { drawLayer(layer) }
          }
          bent = true
        }
        drawPath(outline, Palette.CanvasRaised.copy(alpha = if (bent) 0.58f else opacity))
      }
}

/** The bevel shader and its blur and tone, rebuilt only when the glass changes size. */
@RequiresApi(33)
private class Lens {
  private val shader = RuntimeShader(BEVEL)
  private var key: FloatArray? = null
  private var effect: RenderEffect? = null

  fun effect(size: Size, margin: Float, radius: Float, bevel: Float, bend: Float, blur: Float): RenderEffect {
    val k = floatArrayOf(size.width, size.height, margin, radius, bevel, bend, blur)
    effect?.let { if (k.contentEquals(key)) return it }
    shader.setFloatUniform("origin", margin, margin)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", radius)
    shader.setFloatUniform("bevel", bevel)
    shader.setFloatUniform("bend", bend)
    val tone =
        android.graphics.ColorMatrix().apply {
          setSaturation(1.25f)
          postConcat(android.graphics.ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
        }
    val bent = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val soft = android.graphics.RenderEffect.createBlurEffect(blur, blur, bent, Shader.TileMode.CLAMP)
    val toned = android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(tone), soft)
    return toned.asComposeRenderEffect().also {
      effect = it
      key = k
    }
  }

  companion object {
    /** Samples move inward near the rim, by the square of how close to the rim they are. */
    const val BEVEL =
        """
        uniform shader content;
        uniform float2 origin;
        uniform float2 size;
        uniform float radius;
        uniform float bevel;
        uniform float bend;

        half4 main(float2 coord) {
          float2 hs = size * 0.5;
          float2 c = coord - origin - hs;
          float2 q = abs(c) - (hs - radius);
          float2 qp = max(q, 0.0);
          float depth = radius - length(qp) - min(max(q.x, q.y), 0.0);
          if (depth <= 0.0 || depth >= bevel) { return content.eval(coord); }
          float2 n;
          if (qp.x > 0.0 || qp.y > 0.0) { n = normalize(qp) * sign(c); }
          else if (q.x > q.y) { n = float2(sign(c.x), 0.0); }
          else { n = float2(0.0, sign(c.y)); }
          float t = 1.0 - depth / bevel;
          return content.eval(coord - n * (t * t) * bend);
        }
        """
  }
}
