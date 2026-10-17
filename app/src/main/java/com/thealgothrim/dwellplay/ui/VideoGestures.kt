package com.thealgothrim.dwellplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What the picture's double-tap skipping is showing: which side and how far in total. */
@Stable
class SkipFeedback {
  /** -1 back, 1 forward, 0 nothing on show. */
  var side by mutableIntStateOf(0)
  var seconds by mutableIntStateOf(0)
  /** Changes on every skip, so the fade-out timer restarts. */
  var stamp by mutableLongStateOf(0L)

  fun skipped(dir: Int, by: Int, now: Long) {
    seconds = if (dir == side) seconds + by else by
    side = dir
    stamp = now
  }
}

/**
 * Video-player taps, as on YouTube and MX Player: one tap shows or hides the controls; a double tap
 * on the left or right third skips back or forward; more taps on that side straight after keep
 * skipping; a double tap in the middle plays or pauses. Taps that a button or the seek bar takes
 * never reach here.
 */
fun Modifier.videoTaps(
    enabled: Boolean = true,
    onTap: () -> Unit,
    onSkip: (dir: Int) -> Unit,
    onPlayPause: () -> Unit,
): Modifier =
    if (!enabled) this
    else this.pointerInput(Unit) {
      var streakSide = 0
      var streakUntil = 0L
      fun sideOf(x: Float): Int = when {
        x < size.width * 0.35f -> -1
        x > size.width * 0.65f -> 1
        else -> 0
      }
      awaitEachGesture {
        awaitFirstDown()
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        val side = sideOf(up.position.x)
        // Mid-streak: every tap on the same side skips again, straight away.
        if (side != 0 && side == streakSide && up.uptimeMillis < streakUntil) {
          up.consume()
          onSkip(side)
          streakUntil = up.uptimeMillis + STREAK_MS
          return@awaitEachGesture
        }
        streakSide = 0
        up.consume()
        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
        if (second == null) {
          onTap()
          return@awaitEachGesture
        }
        val up2 = waitForUpOrCancellation() ?: return@awaitEachGesture
        up2.consume()
        when (val side2 = sideOf(up2.position.x)) {
          0 -> onPlayPause()
          else -> {
            onSkip(side2)
            streakSide = side2
            streakUntil = up2.uptimeMillis + STREAK_MS
          }
        }
      }
    }

private const val STREAK_MS = 800L

/** The round "10 s" marker on the side being skipped, gone a moment after the last tap. */
@Composable
fun BoxScope.SkipMarker(feedback: SkipFeedback) {
  val dims = LocalDims.current
  val type = LocalType.current
  val stamp by rememberUpdatedState(feedback.stamp)
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(stamp) {
    if (stamp == 0L) return@LaunchedEffect
    visible = true
    delay(STREAK_MS)
    visible = false
    feedback.side = 0
    feedback.seconds = 0
  }
  val back = feedback.side < 0
  Box(
      Modifier.align(if (back) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.35f),
      contentAlignment = Alignment.Center,
  ) {
    AnimatedVisibility(visible && feedback.side != 0, enter = fadeIn(), exit = fadeOut()) {
      Column(
          Modifier.size(dims.play * 1.3f).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
      ) {
        Icon(if (back) Icons.Replay else Icons.Forward, null, tint = Palette.Text, modifier = Modifier.size(dims.icon))
        Text("${feedback.seconds} s", style = type.button.copy(fontWeight = FontWeight.SemiBold, color = Palette.Text))
      }
    }
  }
}
