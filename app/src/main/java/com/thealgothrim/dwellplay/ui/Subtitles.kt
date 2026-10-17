package com.thealgothrim.dwellplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView

/**
 * Subtitles over native video. The Compose surface draws none, so this is Media3's own subtitle
 * view (text and picture subtitles, the phone's caption style). [raised] lifts them above the
 * full-screen controls while those show. It takes no touches, so taps reach the picture.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun Subtitles(player: Player?, raised: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val view = remember {
    SubtitleView(context).apply {
      setUserDefaultStyle()
      setUserDefaultTextSize()
      isClickable = false
      isFocusable = false
    }
  }
  DisposableEffect(player, view) {
    val listener =
        object : Player.Listener {
          override fun onCues(cueGroup: CueGroup) {
            view.setCues(cueGroup.cues)
          }
        }
    player?.addListener(listener)
    view.setCues(player?.currentCues?.cues)
    onDispose {
      player?.removeListener(listener)
      view.setCues(null)
    }
  }
  AndroidView(factory = { view }, modifier = modifier, update = { it.setBottomPaddingFraction(if (raised) 0.24f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION) })
}
