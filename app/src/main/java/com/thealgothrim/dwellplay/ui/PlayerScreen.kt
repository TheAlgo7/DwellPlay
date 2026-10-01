package com.thealgothrim.dwellplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import com.thealgothrim.dwellplay.core.policy.SafetyMachine
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.core.policy.formatClock
import com.thealgothrim.dwellplay.data.PictureFit
import com.thealgothrim.dwellplay.playback.PlayerHub
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalHostCorners
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import com.thealgothrim.dwellplay.ui.theme.isCar
import com.thealgothrim.dwellplay.youtube.YouTubeEmbed
import com.thealgothrim.dwellplay.youtube.openInYouTube
import kotlinx.coroutines.delay

/**
 * The player. On a wide screen (the Curvv's 1920x720 panel) it's a theater and a rail: the 16:9
 * picture at full height with the controls beside it. Nothing is ever drawn over YouTube's player;
 * native video gets tap-to-show controls only in full-screen theater.
 */
@Composable
fun PlayerScreen(screen: Screen, wide: Boolean, onClose: () -> Unit, actions: AppActions) {
  val hub = AppGraph.hub
  val now by hub.now.collectAsState()
  val np = now ?: return
  var theater by rememberSaveable { mutableStateOf(false) }

  BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
    val landscape = maxWidth > maxHeight
    // The rail needs room: a car screen or a tablet. A phone on its side goes straight to full screen.
    val roomy = screen.isCar || (wide && maxWidth >= 840.dp && maxHeight >= 480.dp)
    when {
      roomy && !theater -> {
        val minRail = if (screen.isCar) 420.dp else 320.dp
        val rail = (maxWidth - maxHeight * 16f / 9f).coerceIn(minRail, maxWidth * 0.45f)
        Row(Modifier.fillMaxSize()) {
          Stage(np, Modifier.weight(1f).fillMaxHeight(), onClose, actions, overlay = false)
          Rail(np, Modifier.width(rail).fillMaxHeight(), onClose, onTheater = { theater = true })
        }
      }
      roomy || landscape ->
          FullScreen(np, onClose, actions, onExit = if (roomy) ({ theater = false }) else null)
      else -> PhonePortrait(np, onClose, actions)
    }
  }
}

/** Full-screen picture. Native video: controls over it on tap. YouTube: a slim column beside it. */
@Composable
private fun FullScreen(np: PlayerHub.NowPlaying, onClose: () -> Unit, actions: AppActions, onExit: (() -> Unit)?) {
  val dims = LocalDims.current
  val hub = AppGraph.hub
  val ui by hub.ui.collectAsState()
  if (np.isYouTube) {
    Row(Modifier.fillMaxSize()) {
      Stage(np, Modifier.weight(1f).fillMaxHeight(), onClose, actions, overlay = false)
      Column(
          // Starts below Android Auto's Exit button, which sits in this corner.
          Modifier.width(dims.touch + 16.dp).fillMaxHeight().background(Palette.Canvas).padding(top = 8.dp + LocalHostCorners.current.height, bottom = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.SpaceBetween,
      ) {
        IconAction(Icons.Back, "Close player", onClick = onClose)
        IconAction(if (ui.playing) Icons.Pause else Icons.Play, if (ui.playing) "Pause" else "Play", onClick = hub::togglePlay, filled = true)
        IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = np.hasNext || (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null)
        if (onExit != null) IconAction(Icons.TheaterExit, "Leave full screen", onClick = onExit) else Spacer(Modifier.size(dims.touch))
      }
    }
  } else {
    Stage(np, Modifier.fillMaxSize(), onClose, actions, overlay = true, onExitTheater = onExit)
  }
}

@Composable
private fun PhonePortrait(np: PlayerHub.NowPlaying, onClose: () -> Unit, actions: AppActions) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(Modifier.fillMaxSize().background(Palette.Canvas)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Text("Now playing", style = type.title)
    }
    Stage(np, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onClose, actions, overlay = false)
    Column(Modifier.fillMaxSize().padding(horizontal = dims.gutter)) {
      Spacer(Modifier.height(dims.gap))
      Details(np)
      Spacer(Modifier.height(dims.gap / 2))
      Progress()
      Controls(np)
      SecondaryControls(np, onTheater = null)
      UpNext(np, Modifier.weight(1f))
    }
  }
}

/** The side rail on wide screens: what's playing, the controls, what's next. */
@Composable
private fun Rail(np: PlayerHub.NowPlaying, modifier: Modifier, onClose: () -> Unit, onTheater: () -> Unit) {
  val dims = LocalDims.current
  Column(modifier.background(Palette.Canvas).padding(horizontal = dims.gap / 2, vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      // On Android Auto its Exit button owns the top-right corner, so these sit next to Back.
      if (LocalHostCorners.current.end == 0.dp) Spacer(Modifier.weight(1f))
      SecondaryControls(np, onTheater)
    }
    Details(np, Modifier.padding(horizontal = 8.dp))
    Progress(Modifier.padding(horizontal = 8.dp))
    Controls(np)
    UpNext(np, Modifier.weight(1f))
  }
}

@Composable
private fun Details(np: PlayerHub.NowPlaying, modifier: Modifier = Modifier) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(modifier) {
    Text(np.video.title, style = type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(np.video.type.icon(), null, tint = Palette.Text3, modifier = Modifier.size(dims.iconSmall).padding(end = 4.dp))
      Text(listOfNotNull(np.video.subtitle, np.video.type.label()).distinct().joinToString(" · "), style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun Progress(modifier: Modifier = Modifier) {
  val hub = AppGraph.hub
  val type = LocalType.current
  val dims = LocalDims.current
  val ui by hub.ui.collectAsState()
  var dragging by remember { mutableStateOf<Float?>(null) }
  val duration = ui.durationMs
  Column(modifier.fillMaxWidth()) {
    if (duration > 0) {
      val fraction = dragging ?: (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
      Slider(
          value = fraction,
          onValueChange = { dragging = it },
          onValueChangeFinished = {
            dragging?.let { hub.seekTo((it * duration).toLong()) }
            dragging = null
          },
          modifier = Modifier.fillMaxWidth().height(dims.touch * 0.7f).semantics { contentDescription = "Position" },
          colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceTop),
      )
      Row(Modifier.fillMaxWidth()) {
        Text(formatClock(((dragging ?: -1f).takeIf { it >= 0 }?.let { (it * duration).toLong() }) ?: ui.positionMs), style = type.meta)
        Spacer(Modifier.weight(1f))
        Text(formatClock(duration), style = type.meta)
      }
    } else {
      Text(if (ui.playing) "Live" else "", style = type.meta, modifier = Modifier.padding(vertical = 8.dp))
    }
  }
}

@Composable
private fun Controls(np: PlayerHub.NowPlaying) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val ui by hub.ui.collectAsState()
  val safety by hub.safety.collectAsState()
  // While the picture is hidden for driving there's nothing to press here.
  val live = safety !is SafetyMachine.State.Hidden
  val playlist = (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (live) 1f else 0.4f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
    IconAction(Icons.Previous, "Previous video", onClick = hub::previous, enabled = live)
    SeekButton(back = true, enabled = live)
    IconAction(
        if (ui.playing) Icons.Pause else Icons.Play,
        if (ui.playing) "Pause" else "Play",
        onClick = hub::togglePlay,
        size = dims.play,
        iconSize = dims.iconLarge,
        filled = true,
        enabled = live,
    )
    SeekButton(back = false, enabled = live)
    IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = live && (np.hasNext || playlist))
  }
}

/** Skip 10 seconds, labelled in words for TalkBack and rotary focus. */
@Composable
private fun SeekButton(back: Boolean, enabled: Boolean = true) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  Box(
      Modifier.size(dims.touch)
          .clip(RoundedCornerShape(dims.touch / 2))
          .clickable(enabled = enabled, role = Role.Button) { hub.seekBy(if (back) -10_000 else 10_000) }
          .semantics { contentDescription = if (back) "Skip back 10 seconds" else "Skip forward 10 seconds" },
      contentAlignment = Alignment.Center,
  ) {
    Icon(if (back) Icons.Replay else Icons.Forward, null, tint = Palette.Text, modifier = Modifier.size(dims.iconLarge))
    Text("10", style = LocalType.current.small.copy(fontSize = (dims.iconLarge.value * 0.26f).sp, fontWeight = FontWeight.Bold, color = Palette.Text), modifier = Modifier.padding(top = dims.iconLarge * 0.16f))
  }
}

@Composable
private fun SecondaryControls(np: PlayerHub.NowPlaying, onTheater: (() -> Unit)?) {
  val hub = AppGraph.hub
  val prefs by AppGraph.settings.prefs.collectAsState()
  val ui by hub.ui.collectAsState()
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (np.isYouTube || ui.captionsAvailable) {
      IconAction(Icons.Captions, if (prefs.captions) "Turn captions off" else "Turn captions on", onClick = { hub.setCaptions(!prefs.captions) }, tint = if (prefs.captions) Palette.Accent else Palette.Text2)
    }
    if (!np.isYouTube) {
      IconAction(
          Icons.Fit,
          if (prefs.fit == PictureFit.FIT) "Fill the screen" else "Fit the whole picture",
          onClick = { AppGraph.settings.update { it.copy(fit = if (it.fit == PictureFit.FIT) PictureFit.FILL else PictureFit.FIT) } },
          tint = if (prefs.fit == PictureFit.FILL) Palette.Accent else Palette.Text2,
      )
    }
    if (onTheater != null) IconAction(Icons.Theater, "Full screen", onClick = onTheater, tint = Palette.Text2)
  }
}

@Composable
private fun UpNext(np: PlayerHub.NowPlaying, modifier: Modifier) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val type = LocalType.current
  val upcoming = np.queue.drop(np.index + 1)
  Column(modifier.fillMaxWidth()) {
    if (upcoming.isEmpty()) {
      if ((np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null) Text("Playing a YouTube playlist", style = type.meta, modifier = Modifier.padding(8.dp))
      return@Column
    }
    Text("Up next", style = type.title, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
    LazyColumn {
      itemsIndexed(upcoming.take(30), key = { _, v -> v.id }) { i, v ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(dims.corner)).clickable { hub.play(v, np.queue) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Artwork(v, Modifier.height(dims.touch * 0.8f).aspectRatio(16f / 9f))
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(v.title, style = type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            v.durationMs?.let { Text(formatClock(it), style = type.meta) }
          }
        }
      }
    }
  }
}

/** The picture area and every state it can be in. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun Stage(
    np: PlayerHub.NowPlaying,
    modifier: Modifier,
    onClose: () -> Unit,
    actions: AppActions,
    overlay: Boolean,
    onExitTheater: (() -> Unit)? = null,
) {
  val hub = AppGraph.hub
  val safety by hub.safety.collectAsState()
  val problem by hub.problem.collectAsState()
  val loading by hub.loading.collectAsState()
  val youtube by hub.youtube.collectAsState()
  val controller by hub.controller.collectAsState()
  val prefs by AppGraph.settings.prefs.collectAsState()
  val ui by hub.ui.collectAsState()
  var controlsShown by remember { mutableStateOf(true) }
  LaunchedEffect(controlsShown, ui.playing) {
    if (controlsShown && ui.playing) {
      delay(4_000)
      controlsShown = false
    }
  }

  Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
    val p = problem
    val s = safety
    when {
      p != null -> ProblemPanel(p, np, onClose, actions)
      s is SafetyMachine.State.ResumePrompt -> ResumePanel(np, s.audioPlaying, onClose)
      s is SafetyMachine.State.Hidden -> HiddenPanel(np)
      np.descriptor == null || loading -> Poster(np) { Spinner() }
      np.isYouTube -> {
        val request = youtube
        if (request != null) {
          YouTubeEmbed(request, hub, Modifier.fillMaxSize())
        } else {
          Poster(np) { IconAction(Icons.Play, "Play", onClick = hub::togglePlay, size = LocalDims.current.play, iconSize = LocalDims.current.iconLarge, filled = true) }
        }
      }
      else -> {
        ContentFrame(
            player = controller,
            modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controlsShown = !controlsShown },
            contentScale = if (prefs.fit == PictureFit.FIT) ContentScale.Fit else ContentScale.Crop,
            shutter = { Poster(np) { if (ui.buffering || !ui.playing) Spinner() } },
        )
        if (ui.buffering) Spinner()
        if (overlay) {
          AnimatedVisibility(controlsShown, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            OverlayControls(np, onClose, onExitTheater)
          }
        }
      }
    }
  }
}

/** Controls over native video in full screen. Never used over YouTube. */
@Composable
private fun OverlayControls(np: PlayerHub.NowPlaying, onClose: () -> Unit, onExitTheater: (() -> Unit)?) {
  val dims = LocalDims.current
  val type = LocalType.current
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xCC000000), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color(0xE6000000)))) {
    // Shifted right on Android Auto, whose own back arrow sits in the top-left corner.
    Row(Modifier.align(Alignment.TopStart).padding(8.dp).padding(start = LocalHostCorners.current.start), verticalAlignment = Alignment.CenterVertically) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Text(np.video.title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Box(Modifier.align(Alignment.Center)) { Controls(np) }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = dims.gutter, vertical = 8.dp)) {
      Progress()
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SecondaryControls(np, onTheater = null)
        if (onExitTheater != null) IconAction(Icons.TheaterExit, "Leave full screen", onClick = onExitTheater)
      }
    }
  }
}

/** Artwork filling the stage, dimmed, with something on top. */
@Composable
private fun Poster(np: PlayerHub.NowPlaying, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Artwork(np.video, Modifier.fillMaxSize().alpha(0.35f), crop = true)
    content()
  }
}

/** Back from a drive: the picture waits for a tap. */
@Composable
private fun ResumePanel(np: PlayerHub.NowPlaying, audioPlaying: Boolean, onClose: () -> Unit) {
  val hub = AppGraph.hub
  val ui by hub.ui.collectAsState()
  val dims = LocalDims.current
  val type = LocalType.current
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Artwork(np.video, Modifier.fillMaxSize().alpha(0.25f))
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(dims.gutter)) {
      Icon(Icons.Parked, null, tint = Palette.Accent, modifier = Modifier.size(dims.iconLarge))
      Spacer(Modifier.height(dims.gap / 2))
      Text("Parked. Ready when you are.", style = type.heading)
      Spacer(Modifier.height(6.dp))
      Text(
          if (audioPlaying) "The sound kept playing while you drove." else "Paused at ${formatClock(ui.positionMs)} while you drove.",
          style = type.body.copy(color = Palette.Text2),
      )
      Spacer(Modifier.height(dims.gap))
      Row(horizontalArrangement = Arrangement.spacedBy(dims.gap / 2)) {
        ActionButton("Resume video", hub::resumeVideo, icon = Icons.Play, primary = true)
        ActionButton("Close", onClose)
      }
    }
  }
}

/** The picture is hidden (driving). No buttons on purpose: there's nothing to do while moving. */
@Composable
private fun HiddenPanel(np: PlayerHub.NowPlaying) {
  val ui by AppGraph.hub.ui.collectAsState()
  val dims = LocalDims.current
  val type = LocalType.current
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(dims.gutter)) {
    Icon(Icons.Car, null, tint = Palette.Text2, modifier = Modifier.size(dims.iconLarge))
    Spacer(Modifier.height(dims.gap / 2))
    Text("Video is paused", style = type.heading)
    Text("Video is available only while parked.", style = type.body.copy(color = Palette.Text2))
    if (ui.positionMs > 0) Text("Your place is saved at ${formatClock(ui.positionMs)}.", style = type.body.copy(color = Palette.Text2))
    Spacer(Modifier.height(dims.gap / 2))
    Text("No action is needed.", style = type.meta)
  }
}

@Composable
private fun ProblemPanel(p: PlayerHub.Problem, np: PlayerHub.NowPlaying, onClose: () -> Unit, actions: AppActions) {
  val hub = AppGraph.hub
  val context = LocalContext.current
  MessagePanel(Icons.Error, p.title, p.detail, iconTint = Palette.Warning) {
    if (p.canConvert) ActionButton("Ask the server to convert it", hub::askServerToConvert, primary = true)
    if (p.canRetry) ActionButton("Try again", hub::retry, icon = Icons.Refresh, primary = !p.canConvert)
    if (p.openInYouTube) {
      val ref = np.video.ref as? MediaRef.YouTube
      ActionButton("Open in YouTube", { openInYouTube(context, ref?.videoId, ref?.playlistId) }, icon = Icons.Open, primary = true)
    }
    if (p.signIn) {
      val address = AppGraph.library.sources.value.firstOrNull { it.id == np.video.sourceId }?.server?.baseUrl
      ActionButton("Sign in", { actions.dialog(AppDialog.Jellyfin(address)) }, primary = true)
    }
    ActionButton("Close", onClose)
  }
}

private fun Dp.coerceIn(min: Dp, max: Dp): Dp = if (max < min) min else if (this < min) min else if (this > max) max else this
