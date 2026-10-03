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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
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
import com.thealgothrim.dwellplay.ui.theme.LocalScreen
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

  BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Canvas)) {
    val landscape = maxWidth > maxHeight
    // Recompose for the window, not the platform: a narrow car cannot fit a useful control rail.
    val minRail = if (screen.isCar) 448.dp else 320.dp
    val panoramic = (screen.isCar || wide) && maxWidth >= minRail + 640.dp &&
        maxWidth >= maxHeight * 2.1f && maxHeight >= 320.dp
    val stackedMinHeight = LocalHostCorners.current.height + LocalDims.current.touch * 2 + 200.dp
    when {
      maxHeight < stackedMinHeight -> FullScreen(np, onClose, actions, onExit = null)
      theater -> FullScreen(np, onClose, actions, onExit = { theater = false })
      panoramic -> {
        val rail = (maxWidth * 0.30f).coerceIn(minRail, maxWidth * 0.38f)
        Row(Modifier.fillMaxSize()) {
          Stage(np, Modifier.weight(1f).fillMaxHeight(), onClose, actions, overlay = false)
          Rail(np, Modifier.width(rail).fillMaxHeight(), onClose, onTheater = { theater = true })
        }
      }
      landscape && !screen.isCar -> FullScreen(np, onClose, actions, onExit = null)
      else -> StackedPlayer(np, onClose, actions, onTheater = { theater = true })
    }
  }
}

/** Full-screen picture. Native video: controls over it on tap. YouTube: a slim column beside it. */
@Composable
private fun FullScreen(np: PlayerHub.NowPlaying, onClose: () -> Unit, actions: AppActions, onExit: (() -> Unit)?) {
  val dims = LocalDims.current
  val hub = AppGraph.hub
  val ui by hub.ui.collectAsState()
  val safety by hub.safety.collectAsState()
  val transportEnabled = safety !is SafetyMachine.State.Hidden && !(np.isYouTube && safety is SafetyMachine.State.ResumePrompt)
  if (np.isYouTube) {
    Row(Modifier.fillMaxSize()) {
      Stage(np, Modifier.weight(1f).fillMaxHeight(), onClose, actions, overlay = false)
      Column(
          // Starts below Android Auto's Exit button, which sits in this corner.
          // Only this bounded control column scrolls; the adjacent YouTube stage stays fixed.
          Modifier.width(dims.touch + 16.dp).fillMaxHeight().background(Palette.CanvasRaised)
              .padding(top = 8.dp + LocalHostCorners.current.height, bottom = 8.dp)
              .verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        IconAction(Icons.Back, "Close player", onClick = onClose)
        IconAction(if (ui.playing) Icons.Pause else Icons.Play, if (ui.playing) "Pause" else "Play", onClick = hub::togglePlay, filled = true, enabled = transportEnabled)
        IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = transportEnabled && (np.hasNext || (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null))
        if (onExit != null) IconAction(Icons.TheaterExit, "Leave full screen", onClick = onExit)
      }
    }
  } else {
    Stage(np, Modifier.fillMaxSize(), onClose, actions, overlay = true, onExitTheater = onExit)
  }
}

@Composable
private fun StackedPlayer(np: PlayerHub.NowPlaying, onClose: () -> Unit, actions: AppActions, onTheater: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  val corners = LocalHostCorners.current
  Column(Modifier.fillMaxSize().background(Palette.Canvas)) {
    Row(
        Modifier.fillMaxWidth().padding(start = dims.gutter / 2, end = dims.gutter, top = corners.height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Text("Now playing", style = type.meta, modifier = Modifier.weight(1f))
      IconAction(Icons.Theater, "Full screen", onClick = onTheater, tint = Palette.Text2)
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
      // Keep the active picture visible when controls or a long queue scroll. On short car
      // windows the stage is letterboxed instead of consuming the whole control viewport.
      val naturalHeight = maxWidth * 9f / 16f
      val minimumHeight = if (np.isYouTube) 200.dp else 0.dp
      val stageHeight = minOf(maxOf(naturalHeight, minimumHeight), maxOf(maxHeight * 0.5f, minimumHeight))
      val transportFirst = LocalScreen.current.isCar && maxWidth > maxHeight
      Column(Modifier.fillMaxSize()) {
        Stage(np, Modifier.fillMaxWidth().height(stageHeight), onClose, actions, overlay = false)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
          MediaAtmosphere(
              np.video,
              Modifier.fillMaxWidth().padding(horizontal = dims.gutter, vertical = dims.gap).chrome(28.dp),
          ) {
            Column(Modifier.fillMaxWidth().padding(dims.gap)) {
              if (transportFirst) {
                Controls(np)
                Spacer(Modifier.height(dims.gap))
              }
              Details(np)
              Spacer(Modifier.height(dims.gap))
              Progress()
              if (!transportFirst) Controls(np)
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SecondaryControls(np, onTheater = null)
              }
              Spacer(Modifier.height(dims.gap))
              ExpandableQueue(np)
            }
          }
          Spacer(Modifier.height(dims.gap))
        }
      }
    }
  }
}

/** An inset control surface beside the picture. Artwork atmosphere never covers the live player. */
@Composable
private fun Rail(np: PlayerHub.NowPlaying, modifier: Modifier, onClose: () -> Unit, onTheater: () -> Unit) {
  val dims = LocalDims.current
  val corners = LocalHostCorners.current
  MediaAtmosphere(np.video, modifier.padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 16.dp).chrome(28.dp)) {
    Column(Modifier.fillMaxSize().padding(horizontal = dims.gap / 2, vertical = 8.dp)) {
      // Keep the host-safe header fixed: queue scrolling must never move actions into Exit's corner.
      Spacer(Modifier.height(corners.height))
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Back, "Close player", onClick = onClose)
        Spacer(Modifier.weight(1f))
        SecondaryControls(np, onTheater)
      }
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(dims.gap / 2))
        Details(np, Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(dims.gap / 2))
        Progress(Modifier.padding(horizontal = 8.dp))
        Controls(np)
        Spacer(Modifier.height(dims.gap / 2))
        ExpandableQueue(np, Modifier.padding(horizontal = 8.dp))
      }
    }
  }
}

@Composable
private fun Details(np: PlayerHub.NowPlaying, modifier: Modifier = Modifier) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(modifier) {
    Text(
        np.video.title,
        style = if (LocalScreen.current.isCar) type.title else type.heading.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
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
  val safety by hub.safety.collectAsState()
  val now by hub.now.collectAsState()
  val seekEnabled = safety !is SafetyMachine.State.Hidden && !(now?.isYouTube == true && safety is SafetyMachine.State.ResumePrompt)
  var dragging by remember { mutableStateOf<Float?>(null) }
  val duration = ui.durationMs
  Column(modifier.fillMaxWidth()) {
    if (duration > 0) {
      val fraction = dragging ?: (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
      Slider(
          value = fraction,
          enabled = seekEnabled,
          onValueChange = { dragging = it },
          onValueChangeFinished = {
            dragging?.let { hub.seekTo((it * duration).toLong()) }
            dragging = null
          },
          modifier = Modifier.fillMaxWidth().height(dims.touch).semantics { contentDescription = "Position" },
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
  val live = safety !is SafetyMachine.State.Hidden && !(np.isYouTube && safety is SafetyMachine.State.ResumePrompt)
  val playlist = (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null
  val playPause: @Composable () -> Unit = {
      IconAction(
          if (ui.playing) Icons.Pause else Icons.Play,
          if (ui.playing) "Pause" else "Play",
          onClick = hub::togglePlay,
          size = dims.play,
          iconSize = dims.iconLarge,
          filled = true,
          enabled = live,
      )
  }
  BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (live) 1f else 0.4f)) {
    if (maxWidth < dims.touch * 4 + dims.play) {
      Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
          IconAction(Icons.Previous, "Previous video", onClick = hub::previous, enabled = live)
          playPause()
          IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = live && (np.hasNext || playlist))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
          SeekButton(back = true, enabled = live)
          Spacer(Modifier.width(dims.gap))
          SeekButton(back = false, enabled = live)
        }
      }
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Previous, "Previous video", onClick = hub::previous, enabled = live)
        SeekButton(back = true, enabled = live)
        playPause()
        SeekButton(back = false, enabled = live)
        IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = live && (np.hasNext || playlist))
      }
    }
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
private fun ExpandableQueue(np: PlayerHub.NowPlaying, modifier: Modifier = Modifier) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val type = LocalType.current
  val upcoming = np.queue.drop(np.index + 1)
  val playlist = (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null
  var expanded by rememberSaveable { mutableStateOf(false) }
  Column(modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier.fillMaxWidth().heightIn(min = dims.touch)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { contentDescription = if (expanded) "Hide queue" else "Show queue, ${upcoming.size} videos up next" }
            .padding(dims.gap / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Queue, null, tint = Palette.Text2, modifier = Modifier.size(dims.icon))
      Spacer(Modifier.width(dims.gap / 2))
      Column(Modifier.weight(1f)) {
        Text("Up next", style = type.title)
        Text(
            upcoming.firstOrNull()?.title ?: if (playlist) "YouTube playlist" else "Nothing queued yet",
            style = type.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(Modifier.width(8.dp))
      Text(if (expanded) "Hide" else "View", style = type.button.copy(color = Palette.Text2))
    }
    AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
      Column(Modifier.fillMaxWidth().padding(bottom = dims.gap / 2)) {
        if (upcoming.isEmpty()) {
          Text(
              if (playlist) "YouTube continues with the next video in this playlist." else "Choose another video from your library to keep watching.",
              style = type.body.copy(color = Palette.Text2),
              modifier = Modifier.padding(dims.gap),
          )
        }
        upcoming.take(30).forEach { v ->
          Row(
              Modifier.fillMaxWidth().heightIn(min = dims.touch)
                  .clip(RoundedCornerShape(dims.corner))
                  .clickable(role = Role.Button) { hub.play(v, np.queue) }
                  .padding(dims.gap / 2),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Artwork(v, Modifier.width(if (LocalScreen.current.isCar) 104.dp else 88.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(dims.gap / 2))
            Column(Modifier.weight(1f)) {
              Text(v.title, style = type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
              v.durationMs?.let { Text(formatClock(it), style = type.meta) }
            }
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
  val corners = LocalHostCorners.current
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xCC000000), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color(0xE6000000)))) {
    // Shifted right on Android Auto, whose own back arrow sits in the top-left corner.
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp).padding(start = corners.start, end = corners.end), verticalAlignment = Alignment.CenterVertically) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Text(np.video.title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    Box(Modifier.align(Alignment.Center).padding(horizontal = dims.gutter).widthIn(max = 720.dp).fillMaxWidth().chrome(dims.play).padding(horizontal = dims.gap / 2, vertical = 4.dp)) {
      Controls(np)
    }
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResumePanel(np: PlayerHub.NowPlaying, audioPlaying: Boolean, onClose: () -> Unit) {
  val hub = AppGraph.hub
  val ui by hub.ui.collectAsState()
  val dims = LocalDims.current
  val type = LocalType.current
  BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Artwork(np.video, Modifier.fillMaxSize().alpha(0.22f))
    Box(Modifier.fillMaxSize().background(Palette.Canvas.copy(alpha = 0.25f)))
    // The bounded card can scroll rather than losing its Resume action on a short display.
    val inset = if (LocalScreen.current.isCar) 16.dp else 8.dp
    val safeTop = LocalHostCorners.current.height
    val cardHeight = maxHeight - safeTop - inset * 2
    Column(
        Modifier.padding(start = inset, end = inset, top = safeTop + inset, bottom = inset)
            .widthIn(max = 640.dp).heightIn(max = if (cardHeight < 1.dp) 1.dp else cardHeight)
            .chrome(24.dp).verticalScroll(rememberScrollState()).padding(dims.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.gap / 2)) {
        Icon(Icons.Parked, null, tint = Palette.Text, modifier = Modifier.size(dims.icon))
        Text("Parked. Ready when you are.", style = type.heading, textAlign = TextAlign.Center)
      }
      Spacer(Modifier.height(8.dp))
      Text(
          if (audioPlaying) "Sound is playing · ${formatClock(ui.positionMs)}" else "Paused at ${formatClock(ui.positionMs)}",
          style = type.body.copy(color = Palette.Text2),
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(dims.gap))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(dims.gap / 2, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(dims.gap / 2)) {
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
  val sources by AppGraph.library.sources.collectAsState()
  MessagePanel(Icons.Error, p.title, p.detail, iconTint = Palette.Warning) {
    if (p.canConvert) ActionButton("Ask the server to convert it", hub::askServerToConvert, primary = true)
    if (p.canRetry) ActionButton("Try again", hub::retry, icon = Icons.Refresh, primary = !p.canConvert)
    if (p.openInYouTube) {
      val ref = np.video.ref as? MediaRef.YouTube
      ActionButton("Open in YouTube", { openInYouTube(context, ref?.videoId, ref?.playlistId) }, icon = Icons.Open, primary = true)
    }
    if (p.signIn) {
      val address = sources.firstOrNull { it.id == np.video.sourceId }?.server?.baseUrl
      ActionButton("Sign in", { actions.dialog(AppDialog.Jellyfin(address)) }, primary = true)
    }
    ActionButton("Close", onClose)
  }
}

private fun Dp.coerceIn(min: Dp, max: Dp): Dp = if (max < min) min else if (this < min) min else if (this > max) max else this
