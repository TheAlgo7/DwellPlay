package com.thealgothrim.dwellplay.ui

import androidx.compose.animation.AnimatedVisibility
import android.app.Activity
import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import com.thealgothrim.dwellplay.core.model.Video
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
 * The player. On a panoramic screen (the Curvv's 1920x720 panel) it's a theater and a rail: the 16:9
 * picture at full height with the controls beside it. Other landscape screens play full screen;
 * portrait stacks the controls under the picture. Nothing is ever drawn over YouTube's player;
 * native video gets tap-to-show controls only in full screen.
 */
@Composable
fun PlayerScreen(screen: Screen, wide: Boolean, onClose: () -> Unit, actions: AppActions, pictureOnCar: Boolean = false) {
  val hub = AppGraph.hub
  val now by hub.now.collectAsState()
  val np = now ?: return
  var theater by rememberSaveable { mutableStateOf(false) }
  var tracksOpen by remember { mutableStateOf(false) }
  // Full screen draws edge to edge under hidden system bars; the other layouts keep clear of them.
  val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

  CompositionLocalProvider(LocalOpenTracks provides { tracksOpen = true }) {
  BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Canvas)) {
    val landscape = maxWidth > maxHeight
    // Recompose for the window, not the platform: a narrow car cannot fit a useful control rail.
    val minRail = if (screen.isCar) 448.dp else 320.dp
    val panoramic = (screen.isCar || wide) && maxWidth >= minRail + 640.dp &&
        maxWidth >= maxHeight * 2.1f && maxHeight >= 320.dp
    val stackedMinHeight = LocalHostCorners.current.height + LocalDims.current.touch * 2 + 200.dp
    when {
      // Android Auto is connected: the phone is the remote, the picture plays on the car screen.
      pictureOnCar -> Box(safe) { RemotePlayer(np, onClose) }
      maxHeight < stackedMinHeight -> FullScreen(np, onClose, actions, onExit = null)
      theater -> FullScreen(np, onClose, actions, onExit = { theater = false })
      panoramic -> {
        val rail = (maxWidth * 0.30f).coerceIn(minRail, maxWidth * 0.38f)
        Row(Modifier.fillMaxSize().then(safe)) {
          Stage(np, Modifier.weight(1f).fillMaxHeight(), onClose, actions, overlay = false)
          Rail(np, Modifier.width(rail).fillMaxHeight(), onClose, onTheater = { theater = true })
        }
      }
      // Any other landscape screen, phone or car (a 16:9 head unit such as the Virtus's): the picture
      // takes the whole screen. The stacked layout under it is for portrait and left a car's video a
      // third of the screen tall.
      landscape -> FullScreen(np, onClose, actions, onExit = null)
      else -> Box(safe) { StackedPlayer(np, onClose, actions, onTheater = { theater = true }) }
    }
  }
  }
  if (tracksOpen) TracksDialog(onDismiss = { tracksOpen = false })
}

/** Opens the audio and subtitles picker of the player on this screen. */
private val LocalOpenTracks = staticCompositionLocalOf<() -> Unit> { {} }

/** Hides the phone's status and navigation bars while full screen shows; a swipe brings them back for a moment. */
@Composable
private fun ImmersiveBars() {
  if (LocalScreen.current.isCar) return
  val view = LocalView.current
  DisposableEffect(view) {
    val window = (view.context as? Activity)?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller?.hide(WindowInsetsCompat.Type.systemBars())
    onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
  }
}

/** Full-screen picture. Native video: controls over it on tap. YouTube: a slim column beside it. */
@Composable
private fun FullScreen(np: PlayerHub.NowPlaying, onClose: () -> Unit, actions: AppActions, onExit: (() -> Unit)?) {
  val dims = LocalDims.current
  val hub = AppGraph.hub
  val playing by playerField { it.playing }
  val safety by hub.safety.collectAsState()
  val transportEnabled = safety !is SafetyMachine.State.Hidden && !(np.isYouTube && safety is SafetyMachine.State.ResumePrompt)
  ImmersiveBars()
  if (np.isYouTube) {
    // YouTube allows nothing over its player, so the controls get a column of their own. On a screen
    // wider than 16:9 (phones, wide car panels) it sits in the black bar beside the picture, which
    // keeps its full height. Where there is no spare width it would shrink the picture, so there it
    // steps aside while the video plays and comes back on pause (YouTube's own controls pause).
    // The stage keeps its place in the layout throughout, so the player never reloads.
    val playingNow by playerField { it.playing }
    // A tap on the picture shows the column (YouTube still gets the tap and shows its own controls);
    // while the video plays it goes again after four seconds, and a tap with it showing hides it.
    var columnShown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(columnShown, playingNow, poke) {
      if (columnShown && playingNow) {
        delay(4_000)
        columnShown = false
      }
    }
    val visible = columnShown || !playingNow
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
      val column = dims.touch + 16.dp
      val spare = maxWidth - maxHeight * 16f / 9f
      val fits = spare >= column
      // In a black bar the column only fades, so the picture never moves; otherwise it gives its
      // width back to the picture.
      val columnWidth by animateDpAsState(if (fits || visible) column else 0.dp, tween(220), label = "ytColumn")
      val columnAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "ytColumnAlpha")
      // Centre the picture when both bars are wide enough; otherwise it stands left of the column.
      val lead = if (spare >= column * 2) spare / 2 - column else 0.dp
    // Watches taps on their way down to the YouTube player without taking them.
    val watchTaps = Modifier.pointerInput(Unit) {
          awaitPointerEventScope {
            var downAt = 0L
            var downPos = androidx.compose.ui.geometry.Offset.Zero
            while (true) {
              val e = awaitPointerEvent(PointerEventPass.Initial)
              val c = e.changes.firstOrNull() ?: continue
              if (c.pressed && !c.previousPressed) {
                downAt = c.uptimeMillis
                downPos = c.position
              } else if (!c.pressed && c.previousPressed) {
                val quick = c.uptimeMillis - downAt < 300 && (c.position - downPos).getDistance() < viewConfiguration.touchSlop
                if (quick) {
                  columnShown = !columnShown
                  poke++
                }
              }
            }
          }
        }
    Row(Modifier.fillMaxSize()) {
      Spacer(Modifier.width(lead))
      Stage(np, Modifier.weight(1f).fillMaxHeight().then(watchTaps), onClose, actions, overlay = false)
      Column(
          // Starts below Android Auto's Exit button, which sits in this corner.
          // Only this bounded control column scrolls; the adjacent YouTube stage stays fixed.
          Modifier.width(columnWidth).fillMaxHeight().alpha(columnAlpha).background(if (fits) Color.Black else Palette.CanvasRaised)
              // Using the column keeps it up for another four seconds.
              .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); poke++ } } }
              .windowInsetsPadding(WindowInsets.displayCutout)
              .padding(top = 8.dp + LocalHostCorners.current.height, bottom = 8.dp)
              .verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Faded out, the buttons are gone too, so nothing invisible can be pressed.
        if (columnAlpha > 0.01f) {
        IconAction(Icons.Back, "Close player", onClick = onClose)
        IconAction(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", onClick = hub::togglePlay, filled = true, enabled = transportEnabled)
        IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = transportEnabled && (np.hasNext || (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null))
        val muted by hub.muted.collectAsState()
        IconAction(if (muted) Icons.Muted else Icons.Sound, if (muted) "Unmute" else "Mute", onClick = { hub.setMuted(!muted) }, tint = if (muted) Palette.Accent else Palette.Text2)
        if (onExit != null) IconAction(Icons.TheaterExit, "Leave full screen", onClick = onExit)
        }
      }
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
        Modifier.fillMaxWidth().padding(start = dims.gutter / 2, end = dims.gutter / 2, top = corners.height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Text("Now playing", style = type.meta, modifier = Modifier.weight(1f))
      SecondaryButtons(np, onTheater)
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
          // The card holds only what it needs: title, seek line, transport. Stretching it to the
          // bottom of a tall phone spread those apart with empty gaps; the room below now goes
          // to the queue, listed like the next videos under a player.
          MediaAtmosphere(
              np.video,
              Modifier.fillMaxWidth().padding(start = dims.gutter, end = dims.gutter, top = dims.gap).chrome(28.dp),
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
            }
          }
          UpNextList(np, Modifier.padding(horizontal = dims.gutter / 2, vertical = dims.gap))
        }
      }
    }
  }
}

/**
 * The phone while Android Auto is connected: artwork, title and the controls, with the picture on
 * the car screen. It never shows the video itself, so nothing plays on a phone a driver can see.
 */
@Composable
private fun RemotePlayer(np: PlayerHub.NowPlaying, onClose: () -> Unit) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val type = LocalType.current
  val onCar by hub.carWindow.collectAsState()
  Column(Modifier.fillMaxSize().background(Palette.Canvas)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = dims.gutter / 2), verticalAlignment = Alignment.CenterVertically) {
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Text("Now playing", style = type.meta, modifier = Modifier.weight(1f))
      SecondaryButtons(np, onTheater = null)
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
      Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
        Artwork(np.video, Modifier.fillMaxSize().alpha(0.3f), crop = true)
        Column(Modifier.padding(dims.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(Icons.Car, null, tint = Palette.Text, modifier = Modifier.size(dims.iconLarge))
          Spacer(Modifier.height(dims.gap / 2))
          Text(if (onCar) "Playing on the car screen" else "Open DwellPlay on the car screen", style = type.heading, textAlign = TextAlign.Center)
          Spacer(Modifier.height(4.dp))
          Text(
              when {
                onCar -> "This phone is the remote while Android Auto is connected."
                np.isYouTube -> "YouTube starts when its player is on the car screen."
                else -> "Videos show on the car screen while Android Auto is connected. The sound is already playing."
              },
              style = type.body.copy(color = Palette.Text2),
              textAlign = TextAlign.Center,
          )
        }
      }
      MediaAtmosphere(np.video, Modifier.fillMaxWidth().padding(start = dims.gutter, end = dims.gutter, top = dims.gutter).chrome(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(dims.gap)) {
          Details(np)
          Spacer(Modifier.height(dims.gap))
          Progress()
          Controls(np)
        }
      }
      UpNextList(np, Modifier.padding(horizontal = dims.gutter / 2, vertical = dims.gap))
    }
  }
}

/** An inset control surface beside the picture. Artwork atmosphere never covers the live player. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Rail(np: PlayerHub.NowPlaying, modifier: Modifier, onClose: () -> Unit, onTheater: () -> Unit) {
  val dims = LocalDims.current
  val railEnd = 16.dp
  MediaAtmosphere(np.video, modifier.padding(start = 8.dp, end = railEnd, top = 16.dp, bottom = 16.dp).chrome(28.dp)) {
    Column(Modifier.fillMaxSize().padding(horizontal = dims.gap / 2, vertical = 8.dp)) {
      // Only Back up top: Android Auto's Exit button covers the display's top-right corner for a
      // few seconds, and squeezing the options beside it wrapped them onto a second line. The
      // options get their own row under the transport instead.
      IconAction(Icons.Back, "Close player", onClick = onClose)
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Details(np, Modifier.padding(horizontal = 8.dp), maxLines = 2)
        Spacer(Modifier.height(dims.gap / 2))
        Progress(Modifier.padding(horizontal = 8.dp), inline = true)
        Controls(np)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { SecondaryButtons(np, onTheater) }
        ExpandableQueue(np, Modifier.padding(horizontal = 8.dp))
      }
    }
  }
}

@Composable
private fun Details(np: PlayerHub.NowPlaying, modifier: Modifier = Modifier, maxLines: Int = 3) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(modifier) {
    Text(
        np.video.title,
        style = if (LocalScreen.current.isCar) type.title else type.heading.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(np.video.type.icon(), null, tint = Palette.Text3, modifier = Modifier.size(dims.iconSmall).padding(end = 4.dp))
      Text(listOfNotNull(np.video.subtitle, np.video.type.label()).distinct().joinToString(" · "), style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Progress(modifier: Modifier = Modifier, inline: Boolean = false) {
  val hub = AppGraph.hub
  val type = LocalType.current
  val dims = LocalDims.current
  val ui by hub.ui.collectAsState()
  val safety by hub.safety.collectAsState()
  val now by hub.now.collectAsState()
  val seekEnabled = safety !is SafetyMachine.State.Hidden && !(now?.isYouTube == true && safety is SafetyMachine.State.ResumePrompt)
  var dragging by remember { mutableStateOf<Float?>(null) }
  val duration = ui.durationMs
  val elapsed = formatClock(((dragging ?: -1f).takeIf { it >= 0 }?.let { (it * duration).toLong() }) ?: ui.positionMs)
  Column(modifier.fillMaxWidth()) {
    if (duration > 0) {
      val fraction = dragging ?: (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
      val interaction = remember { MutableInteractionSource() }
      // The brand board's seek bar: a thin crimson line and a white thumb, with a full-height target.
      val seekBar: @Composable (Modifier) -> Unit = { m ->
        Slider(
            value = fraction,
            enabled = seekEnabled,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
              dragging?.let { hub.seekTo((it * duration).toLong()) }
              dragging = null
            },
            modifier = m.height(dims.touch).semantics { contentDescription = "Position" },
            interactionSource = interaction,
            thumb = { SeekThumb(interaction, seekEnabled) },
            track = { state -> SeekTrack(state.value, seekEnabled) },
        )
      }
      if (inline) {
        // Times either side of the line, so the bar reads as one control.
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(elapsed, style = type.meta, maxLines = 1)
          Spacer(Modifier.width(12.dp))
          seekBar(Modifier.weight(1f))
          Spacer(Modifier.width(12.dp))
          Text(formatClock(duration), style = type.meta, maxLines = 1)
        }
      } else {
        seekBar(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
          Text(elapsed, style = type.meta)
          Spacer(Modifier.weight(1f))
          Text(formatClock(duration), style = type.meta)
        }
      }
    } else {
      Text(if (ui.playing) "Live" else "", style = type.meta, modifier = Modifier.padding(vertical = 8.dp))
    }
  }
}

@Composable
private fun SeekThumb(interaction: MutableInteractionSource, enabled: Boolean) {
  val base = if (LocalScreen.current.isCar) 22.dp else 14.dp
  val pressed by interaction.collectIsPressedAsState()
  val dragged by interaction.collectIsDraggedAsState()
  val size by animateDpAsState(if (pressed || dragged) base * 1.4f else base, tween(150), label = "thumb")
  // A fixed slot, so the track does not shift while the thumb grows under a finger.
  Box(Modifier.size(base * 1.4f), contentAlignment = Alignment.Center) {
    Box(
        Modifier.size(size)
            .dropShadow(CircleShape, Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.5f)))
            .clip(CircleShape)
            .background(if (enabled) Palette.Text else Palette.Text3)
    )
  }
}

@Composable
private fun SeekTrack(fraction: Float, enabled: Boolean) {
  val height = if (LocalScreen.current.isCar) 6.dp else 4.dp
  Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Color.White.copy(alpha = 0.16f))) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(if (enabled) Palette.Accent else Palette.Text3))
  }
}

@Composable
private fun Controls(np: PlayerHub.NowPlaying) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val playing by playerField { it.playing }
  val safety by hub.safety.collectAsState()
  // While the picture is hidden for driving there's nothing to press here.
  val live = safety !is SafetyMachine.State.Hidden && !(np.isYouTube && safety is SafetyMachine.State.ResumePrompt)
  val playlist = (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null
  val playPause: @Composable () -> Unit = {
      IconAction(
          if (playing) Icons.Pause else Icons.Play,
          if (playing) "Pause" else "Play",
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

/** Skip by the length set in Settings (5 or 10 seconds), labelled in words for TalkBack and rotary focus. */
@Composable
private fun SeekButton(back: Boolean, enabled: Boolean = true) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val prefs by AppGraph.settings.prefs.collectAsState()
  val by = prefs.skipSeconds
  Box(
      Modifier.size(dims.touch)
          .clip(RoundedCornerShape(dims.touch / 2))
          .clickable(enabled = enabled, role = Role.Button) { hub.seekBy((if (back) -by else by) * 1_000L) }
          .semantics { contentDescription = if (back) "Skip back $by seconds" else "Skip forward $by seconds" },
      contentAlignment = Alignment.Center,
  ) {
    Icon(if (back) Icons.Replay else Icons.Forward, null, tint = Palette.Text, modifier = Modifier.size(dims.iconLarge))
    Text("$by", style = LocalType.current.small.copy(fontSize = (dims.iconLarge.value * 0.26f).sp, fontWeight = FontWeight.Bold, color = Palette.Text), modifier = Modifier.padding(top = dims.iconLarge * 0.16f))
  }
}

/** Captions, picture fit and full screen. Emits bare buttons so a Row or a FlowRow can hold them. */
@Composable
private fun SecondaryButtons(np: PlayerHub.NowPlaying, onTheater: (() -> Unit)?) {
  val hub = AppGraph.hub
  val prefs by AppGraph.settings.prefs.collectAsState()
  val captionsAvailable by playerField { it.captionsAvailable }
  val audioChoices by playerField { it.audioTracks.size }
  val openTracks = LocalOpenTracks.current
  val muted by hub.muted.collectAsState()
  IconAction(if (muted) Icons.Muted else Icons.Sound, if (muted) "Unmute" else "Mute", onClick = { hub.setMuted(!muted) }, tint = if (muted) Palette.Accent else Palette.Text2)
  if (np.isYouTube) {
    IconAction(Icons.Captions, if (prefs.captions) "Turn captions off" else "Turn captions on", onClick = { hub.setCaptions(!prefs.captions) }, tint = if (prefs.captions) Palette.Accent else Palette.Text2)
  } else {
    // Dual-audio films and files with subtitles: one button opens the picker for both, so the car's
    // narrow control panel keeps its header on one line.
    if (audioChoices > 1 || captionsAvailable) {
      IconAction(
          if (audioChoices > 1) Icons.Language else Icons.Captions,
          "Audio and subtitles",
          onClick = openTracks,
          tint = if (prefs.captions && captionsAvailable) Palette.Accent else Palette.Text2,
      )
    }
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
        upcoming.take(30).forEach { v -> QueueRow(v, np) }
      }
    }
  }
}

/** The queue as an open list under the phone's player card. */
@Composable
private fun UpNextList(np: PlayerHub.NowPlaying, modifier: Modifier = Modifier) {
  val type = LocalType.current
  val dims = LocalDims.current
  val upcoming = np.queue.drop(np.index + 1)
  val playlist = (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null
  Column(modifier.fillMaxWidth()) {
    Text("Up next", style = type.title, modifier = Modifier.padding(horizontal = dims.gap / 2, vertical = 4.dp))
    if (upcoming.isEmpty()) {
      Text(
          if (playlist) "YouTube continues with the next video in this playlist." else "Nothing queued. Pick another video from your library to keep watching.",
          style = type.meta,
          modifier = Modifier.padding(horizontal = dims.gap / 2),
      )
    }
    upcoming.take(30).forEach { v -> QueueRow(v, np) }
  }
}

@Composable
private fun QueueRow(v: Video, np: PlayerHub.NowPlaying) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.fillMaxWidth().heightIn(min = dims.touch)
          .clip(RoundedCornerShape(dims.corner))
          .clickable(role = Role.Button) { hub.play(v, np.queue) }
          .padding(dims.gap / 2),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Artwork(v, Modifier.width(if (LocalScreen.current.isCar) 104.dp else 120.dp).aspectRatio(16f / 9f))
    Spacer(Modifier.width(dims.gap))
    Column(Modifier.weight(1f)) {
      Text(v.title, style = type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(listOfNotNull(v.durationMs?.let { formatClock(it) }, v.subtitle).joinToString(" · "), style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
  val playing by playerField { it.playing }
  val buffering by playerField { it.buffering }
  var poke by remember { mutableIntStateOf(0) }
  var controlsShown by remember { mutableStateOf(true) }
  val skip = remember { SkipFeedback() }
  val overlayNow by rememberUpdatedState(overlay)
  // Any touch on the controls restarts the four seconds before they fade.
  LaunchedEffect(controlsShown, playing, poke) {
    if (controlsShown && playing) {
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
        // One layer takes the taps for the picture and for the controls over it, so a tap anywhere
        // that isn't a button hides the controls again (the old controls layer swallowed those taps).
        Box(
            Modifier.fillMaxSize().videoTaps(
                onTap = { if (overlayNow) controlsShown = !controlsShown },
                onSkip = { dir ->
                  val by = AppGraph.settings.current.skipSeconds
                  hub.seekBy(dir * by * 1_000L)
                  skip.skipped(dir, by, SystemClock.uptimeMillis())
                  poke++
                },
                onPlayPause = {
                  hub.togglePlay()
                  if (overlayNow) controlsShown = true
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
          ContentFrame(
              player = controller,
              modifier = Modifier.fillMaxSize(),
              contentScale = if (prefs.fit == PictureFit.FIT) ContentScale.Fit else ContentScale.Crop,
              shutter = { Poster(np) { if (buffering || !playing) Spinner() } },
          )
          Subtitles(controller, raised = overlay && controlsShown, modifier = Modifier.fillMaxSize())
          if (buffering) Spinner()
          SkipMarker(skip)
          if (overlay) {
            AnimatedVisibility(controlsShown, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
              OverlayControls(np, onClose, onExitTheater, onTouch = { poke++ })
            }
          }
        }
      }
    }
  }
}

/**
 * Controls over native video in full screen. Never used over YouTube. The picture stays clear: the
 * title sits at the top, and one glass bar along the bottom carries transport, the seek line with
 * its times, and the picture options. Android Auto takes touches in both top corners, so nothing
 * tappable goes up there.
 */
@Composable
private fun OverlayControls(np: PlayerHub.NowPlaying, onClose: () -> Unit, onExitTheater: (() -> Unit)?, onTouch: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  val corners = LocalHostCorners.current
  Box(
      Modifier.fillMaxSize()
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                onTouch()
              }
            }
          }
          .background(Brush.verticalGradient(0f to Color(0x8C000000), 0.22f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xA6000000)))
          // The phone's camera cutout: the title and the bar keep clear of it in landscape.
          .windowInsetsPadding(WindowInsets.displayCutout)
  ) {
    Text(
        np.video.title,
        style = type.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.TopStart).padding(start = dims.gutter + corners.start, end = dims.gutter + corners.end, top = dims.gap),
    )
    BoxWithConstraints(
        Modifier.align(Alignment.BottomCenter).padding(start = dims.gutter, end = dims.gutter, bottom = dims.gap).widthIn(max = 1280.dp).fillMaxWidth()
    ) {
      // One line when everything fits (wide phones, the Curvv). Otherwise two: the seek line on top,
      // the transport centred under it with close on the left and the picture options on the right.
      // A capsule only suits one line; two lines get a rounded panel so the times sit clear of the ends.
      val oneLine = maxWidth >= dims.touch * 10 + 300.dp
      val centred = maxWidth >= dims.touch * 11
      Glass(backdrop = null, corner = if (oneLine) 999.dp else 28.dp, opacity = 0.8f, modifier = Modifier.fillMaxWidth()) {
        Box(
            // Taps between the buttons stay on the bar instead of hiding it.
            Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = if (oneLine) 10.dp else 16.dp, vertical = if (oneLine) 6.dp else 10.dp)
        ) {
          if (oneLine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (onExitTheater == null) IconAction(Icons.Back, "Close player", onClick = onClose)
              BarTransport(np)
              Spacer(Modifier.width(dims.gap))
              Progress(Modifier.weight(1f), inline = true)
              Spacer(Modifier.width(dims.gap / 2))
              SecondaryButtons(np, onTheater = null)
              if (onExitTheater != null) IconAction(Icons.TheaterExit, "Leave full screen", onClick = onExitTheater)
            }
          } else {
            Column {
              Progress(Modifier.padding(horizontal = 4.dp), inline = true)
              Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                  if (onExitTheater == null) IconAction(Icons.Back, "Close player", onClick = onClose)
                  if (!centred) BarTransport(np)
                }
                if (centred) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) { BarTransport(np) }
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                  SecondaryButtons(np, onTheater = null)
                  if (onExitTheater != null) IconAction(Icons.TheaterExit, "Leave full screen", onClick = onExitTheater)
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Previous, the 10-second skips around play or pause, and next, for the full-screen bar. */
@Composable
private fun BarTransport(np: PlayerHub.NowPlaying) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val playing by playerField { it.playing }
  val safety by hub.safety.collectAsState()
  val live = safety !is SafetyMachine.State.Hidden && !(np.isYouTube && safety is SafetyMachine.State.ResumePrompt)
  val playlist = (np.descriptor as? PlaybackDescriptor.YouTube)?.playlistId != null
  IconAction(Icons.Previous, "Previous video", onClick = hub::previous, enabled = live)
  SeekButton(back = true, enabled = live)
  IconAction(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play", onClick = hub::togglePlay, filled = true, enabled = live, iconSize = dims.iconLarge * 0.9f)
  SeekButton(back = false, enabled = live)
  IconAction(Icons.Next, "Next video", onClick = hub::next, enabled = live && (np.hasNext || playlist))
}

/** One field of the player state. Positions tick twice a second; only the seek bar needs them. */
@Composable
private fun <T> playerField(select: (PlayerHub.PlayerUi) -> T): State<T> {
  val ui = AppGraph.hub.ui.collectAsState()
  return remember { derivedStateOf { select(ui.value) } }
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
