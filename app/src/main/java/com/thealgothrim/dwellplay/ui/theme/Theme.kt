package com.thealgothrim.dwellplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thealgothrim.dwellplay.core.policy.Screen

/**
 * Dark Cinema: graphite depth, neutral readable content, crimson for intent.
 * The brand color is for progress and actions, not small text. Safety is never color alone.
 */
object Palette {
  val Canvas = Color(0xFF08090B)
  val CanvasRaised = Color(0xFF101115)
  val Surface = Color(0xFF17181C)
  val SurfaceHigh = Color(0xFF222329)
  val SurfaceTop = Color(0xFF2D2E35)
  val Glass = Color(0xEB17181C)
  val Hairline = Color(0x18F2F2F2)
  val Text = Color(0xFFF2F2F2)
  val Text2 = Color(0xFFA9ABB2)
  val Text3 = Color(0xFF94969F)
  val Accent = Color(0xFFC9183C)
  val OnAccent = Text
  val AccentSoft = Color(0x24C9183C)
  val Warning = Color(0xFFFFB85C)
  val Error = Color(0xFFFF6B6B)
  val Scrim = Color(0xE608090B)
}

/** Sizes that change between a phone and a car screen (Google's automotive numbers for the car). */
@Immutable
data class Dims(
    /** Smallest touch target: 76dp in a car. */
    val touch: Dp,
    /** The main play button. */
    val play: Dp,
    val iconLarge: Dp,
    val icon: Dp,
    val iconSmall: Dp,
    val gutter: Dp,
    val gap: Dp,
    val section: Dp,
    val cardWidth: Dp,
    val corner: Dp,
    val sheetCorner: Dp,
    val rail: Dp,
)

@Immutable
data class DwellType(
    val hero: TextStyle,
    val heading: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val button: TextStyle,
    val small: TextStyle,
)

private fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, line: TextUnit = size * 1.25f, color: Color = Palette.Text) =
    TextStyle(fontSize = size, fontWeight = weight, lineHeight = line, color = color)

val CarDims = Dims(76.dp, 88.dp, 44.dp, 36.dp, 24.dp, 32.dp, 24.dp, 36.dp, 336.dp, 12.dp, 24.dp, 128.dp)
val PhoneDims = Dims(48.dp, 72.dp, 28.dp, 24.dp, 20.dp, 20.dp, 12.dp, 28.dp, 248.dp, 12.dp, 24.dp, 88.dp)

/** Car: 32sp headings, 28sp titles, 24sp is the floor for anything that matters. */
val CarType =
    DwellType(
        hero = style(40.sp, FontWeight.Medium),
        heading = style(32.sp, FontWeight.Medium),
        title = style(28.sp, FontWeight.Medium),
        body = style(24.sp),
        meta = style(24.sp, color = Palette.Text2),
        button = style(24.sp, FontWeight.Medium),
        small = style(24.sp, color = Palette.Text2),
    )

val PhoneType =
    DwellType(
        hero = style(28.sp, FontWeight.Medium),
        heading = style(22.sp, FontWeight.Medium),
        title = style(16.sp, FontWeight.Medium),
        body = style(16.sp),
        meta = style(14.sp, color = Palette.Text2),
        button = style(16.sp, FontWeight.Medium),
        small = style(12.sp, color = Palette.Text2),
    )

val LocalDims = staticCompositionLocalOf { PhoneDims }
val LocalType = staticCompositionLocalOf { PhoneType }
val LocalScreen = staticCompositionLocalOf { Screen.PHONE }

val Screen.isCar: Boolean
  get() = this != Screen.PHONE

/**
 * Android Auto gives a parked app a display 1.31 times denser than the head unit's own (262 dpi
 * against 200 on the Curvv preset, measured on the head unit 2026-09-30). It does that so
 * phone-sized buttons become car-sized. DwellPlay's car layout is car-sized already, so it lays out
 * at the head unit's own scale, the one Android Auto draws its own screens at.
 */
const val ANDROID_AUTO_ZOOM = 1.31f

/**
 * The two top corners where Android Auto draws its own buttons over a parked app for a few seconds
 * (a back arrow on the left, "Exit" on the right). Nothing of ours that can be tapped goes there.
 * All zero on the phone and on Android Automotive OS.
 */
@Immutable
data class HostCorners(val start: Dp = 0.dp, val end: Dp = 0.dp, val height: Dp = 0.dp)

val LocalHostCorners = staticCompositionLocalOf { HostCorners() }
private val AndroidAutoCorners = HostCorners(start = 88.dp, end = 168.dp, height = 80.dp)

@Composable
fun DwellTheme(screen: Screen, content: @Composable () -> Unit) {
  val car = screen.isCar
  val type = if (car) CarType else PhoneType
  val colors =
      darkColorScheme(
          primary = Palette.Accent,
          onPrimary = Palette.OnAccent,
          secondary = Palette.Accent,
          onSecondary = Palette.OnAccent,
          background = Palette.Canvas,
          onBackground = Palette.Text,
          surface = Palette.Surface,
          onSurface = Palette.Text,
          surfaceVariant = Palette.SurfaceHigh,
          onSurfaceVariant = Palette.Text2,
          surfaceContainer = Palette.Surface,
          surfaceContainerHigh = Palette.SurfaceHigh,
          surfaceContainerHighest = Palette.SurfaceTop,
          outline = Palette.Hairline,
          outlineVariant = Palette.Hairline,
          error = Palette.Error,
      )
  val typography =
      Typography(
          displaySmall = type.hero,
          headlineSmall = type.heading,
          titleLarge = type.title,
          titleMedium = type.title,
          bodyLarge = type.body,
          bodyMedium = type.body,
          bodySmall = type.meta,
          labelLarge = type.button,
          labelMedium = type.small,
      )
  val real = LocalDensity.current
  val density = if (screen == Screen.ANDROID_AUTO) Density(real.density / ANDROID_AUTO_ZOOM, real.fontScale) else real
  MaterialTheme(colorScheme = colors, typography = typography) {
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalDims provides if (car) CarDims else PhoneDims,
        LocalType provides type,
        LocalScreen provides screen,
        LocalHostCorners provides if (screen == Screen.ANDROID_AUTO) AndroidAutoCorners else HostCorners(),
        LocalContentColor provides Palette.Text,
        content = content,
    )
  }
}
