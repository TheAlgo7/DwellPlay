package com.thealgothrim.dwellplay.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.car.app.connection.CarConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.car.CarScreens
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.core.policy.formatClock
import com.thealgothrim.dwellplay.sources.PhoneVideos
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalHostCorners
import com.thealgothrim.dwellplay.ui.theme.LocalScreen
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import com.thealgothrim.dwellplay.ui.theme.isCar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

enum class Tab(val label: String, val icon: ImageVector) {
  HOME("Home", Icons.Home),
  LIBRARY("Library", Icons.Library),
  SEARCH("Search", Icons.Search),
  SOURCES("Sources", Icons.Server),
  SETTINGS("Settings", Icons.Settings),
}

private val PrimaryTabs = listOf(Tab.HOME, Tab.LIBRARY, Tab.SEARCH, Tab.SOURCES)

/** A Jellyfin folder being browsed in the library. */
data class BrowseTarget(val sourceId: String, val parentId: String?, val title: String)

sealed interface AppDialog {
  data object AddYouTube : AppDialog
  data object AddStream : AppDialog
  data class Jellyfin(val address: String? = null) : AppDialog
  data class Manage(val video: Video) : AppDialog
}

/** Where the user is: four primary destinations, settings, a player and a drill-down. */
class AppNav {
  var tab by mutableStateOf(Tab.HOME)
  var playerOpen by mutableStateOf(false)
  var librarySource by mutableStateOf<String?>(null)
  val browse = mutableStateListOf<BrowseTarget>()
  var dialog by mutableStateOf<AppDialog?>(null)
}

/** One-line messages shown at the bottom ("Added to DwellPlay"). */
object AppMessages {
  val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)

  fun say(message: String) {
    flow.tryEmit(message)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwellApp(screen: Screen, restricted: Boolean, nav: AppNav) {
  val hub = AppGraph.hub
  val library = AppGraph.library
  val now by hub.now.collectAsState()
  val connection by CarScreens.connection.collectAsState()
  // While Android Auto is connected the phone is the remote and the picture belongs to the car.
  val pictureOnCar = screen == Screen.PHONE && (connection == CarConnection.CONNECTION_TYPE_PROJECTION || CarScreens.pretendProjecting)

  val phonePermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { library.refreshPhone() }
  val folderPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
          val s = library.addFolder(uri)
          AppMessages.say("Added ${s.name}")
        }
      }
  val actions =
      remember(nav, screen) {
        AppActions(
            open = { video, queue ->
              if (video.isFolder && video.type == SourceType.JELLYFIN) {
                nav.tab = Tab.LIBRARY
                nav.librarySource = video.sourceId
                val id = (video.ref as? com.thealgothrim.dwellplay.core.model.MediaRef.Jellyfin)?.itemId
                nav.browse.add(BrowseTarget(video.sourceId, id, video.title))
                nav.playerOpen = false
              } else {
                hub.play(video, queue)
                nav.playerOpen = true
                if (screen == Screen.PHONE && CarScreens.projecting) hub.sendPictureToCar()
              }
            },
            askPhoneAccess = { phonePermission.launch(PhoneVideos.permissions()) },
            pickFolder = { folderPicker.launch(null) },
            dialog = { nav.dialog = it },
            goTo = { nav.tab = it; nav.playerOpen = false },
        )
      }

  // The car window opens its player for a title picked on the phone.
  if (screen.isCar) {
    val wanted by hub.carPlayerWanted.collectAsState()
    LaunchedEffect(wanted, now) {
      if (wanted && now != null) {
        nav.playerOpen = true
        nav.dialog = null
        hub.carPlayerOpened()
      }
    }
  }
  // Leaving the phone's remote never stops what the car is showing.
  val leavePlayer = {
    if (!pictureOnCar) hub.leftPlayerScreen()
    nav.playerOpen = false
  }

  BackHandler(enabled = nav.playerOpen || nav.browse.isNotEmpty() || nav.tab != Tab.HOME) {
    when {
      nav.playerOpen -> {
        leavePlayer()
        nav.playerOpen = false
      }
      nav.tab == Tab.LIBRARY && nav.browse.isNotEmpty() -> nav.browse.removeAt(nav.browse.lastIndex)
      else -> nav.tab = Tab.HOME
    }
  }

  // Browsing content runs under the gesture bar; the floating chrome and the player keep clear of it.
  // The keyboard still pushes everything up.
  BoxWithConstraints(
      Modifier.fillMaxSize().background(Palette.Canvas)
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
          .windowInsetsPadding(WindowInsets.ime)
  ) {
    // A portrait car needs its width for content. Only a roomy landscape window gets the rail.
    // Short windows also use the dock, so the pinned Settings action never squeezes a tab's target.
    val roomForRail = maxHeight >= if (screen.isCar) 360.dp else 240.dp
    val wide = roomForRail && if (screen.isCar) maxWidth >= 840.dp && maxWidth > maxHeight
        else maxWidth >= 840.dp || (maxWidth > maxHeight && maxWidth >= 600.dp)
    val current = now
    val density = LocalDensity.current
    val corners = LocalHostCorners.current
    // Height of whatever floats at the bottom (mini player, dock), so lists can scroll clear of it.
    var chromeHeight by remember { mutableStateOf(0.dp) }
    if (nav.playerOpen && current != null) {
      Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
        PlayerScreen(screen = screen, wide = wide, onClose = leavePlayer, actions = actions, pictureOnCar = pictureOnCar)
      }
    } else {
      val backdrop = rememberBackdrop()
      val railInset = if (wide) RailMargin + LocalDims.current.touch + RailPadding * 2 else 0.dp
      CompositionLocalProvider(LocalChromeInsets provides ChromeInsets(start = railInset, bottom = chromeHeight)) {
        // The navigation floats over this, and its glass bends what scrolls underneath.
        Column(Modifier.fillMaxSize().backdropSource(backdrop).background(Palette.Canvas)) {
          // On compact projected windows, reserve both host corners for every browsing screen.
          if (!wide) Spacer(Modifier.height(corners.height))
          Box(Modifier.weight(1f)) { TabContent(nav, screen, actions, railNavigation = wide) }
        }
      }
      if (wide) {
        // Android Auto puts its own back arrow in the top-left corner: the rail stays below it.
        Box(
            Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(start = RailMargin, top = corners.height + 12.dp, bottom = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
          NavRail(nav, backdrop)
        }
      }
      Column(
          Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = railInset)
              .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }
              .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (current != null) {
          MiniPlayer(backdrop, onOpen = { nav.playerOpen = true })
          Spacer(Modifier.height(10.dp))
        }
        // The dock steps aside while typing, so search results keep the room above the keyboard.
        if (!wide && !WindowInsets.isImeVisible) Dock(nav, backdrop)
        Spacer(Modifier.height(if (wide) 16.dp else 14.dp))
      }
    }

    // Dialog has its own Android window, so a Compose driving cover cannot hide it.
    // Keep the pending dialog in navigation state and restore it only once restrictions clear.
    if (!restricted) nav.dialog?.let { d -> DialogHost(d, onDismiss = { nav.dialog = null }, actions = actions) }

    MessageBar(
        Modifier.align(Alignment.BottomCenter).padding(bottom = if (nav.playerOpen) 24.dp else chromeHeight + 8.dp),
        visible = !nav.playerOpen || current?.isYouTube != true,
    )

    // Android Automotive OS driving restrictions: nothing to browse or watch until the car stops.
    if (restricted) DrivingCover()
  }
}

/** What screens can ask the app to do. */
class AppActions(
    val open: (Video, List<Video>) -> Unit,
    val askPhoneAccess: () -> Unit,
    val pickFolder: () -> Unit,
    val dialog: (AppDialog) -> Unit,
    val goTo: (Tab) -> Unit,
)

@Composable
private fun TabContent(nav: AppNav, screen: Screen, actions: AppActions, railNavigation: Boolean) {
  when (nav.tab) {
    Tab.HOME -> HomeScreen(screen, nav, actions, railNavigation)
    Tab.LIBRARY -> LibraryScreen(nav, actions)
    Tab.SEARCH -> SearchScreen(actions)
    Tab.SOURCES -> SourcesScreen(actions)
    Tab.SETTINGS -> SettingsScreen(screen)
  }
}

private val RailMargin = 16.dp
private val RailPadding = 8.dp

/** WearWise's label slide: 240ms on its own curve, the label trailing the pill by 40ms. */
private val Glide = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Wide windows (the Curvv's panel, landscape phones): the dock turned on its side. A glass capsule
 * of icon tabs beside the content, the current one a crimson disc, Settings below a hairline.
 */
@Composable
private fun NavRail(nav: AppNav, backdrop: Backdrop) {
  val dims = LocalDims.current
  Glass(backdrop, corner = 999.dp) {
    Column(
        Modifier.padding(RailPadding).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      for (tab in PrimaryTabs) RailItem(tab, selected = nav.tab == tab) { nav.tab = tab }
      Box(Modifier.padding(vertical = 4.dp).width(dims.touch / 2).height(1.dp).background(Palette.Hairline))
      RailItem(Tab.SETTINGS, selected = nav.tab == Tab.SETTINGS) { nav.tab = Tab.SETTINGS }
    }
  }
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
  val dims = LocalDims.current
  val fill by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, tween(220, easing = Glide), label = "rail fill")
  val tint by animateColorAsState(if (selected) Palette.OnAccent else Palette.Text2, tween(200), label = "rail tint")
  Box(
      Modifier.size(dims.touch).clip(CircleShape).background(fill)
          .clickable(role = Role.Tab, onClick = onClick)
          .semantics { this.selected = selected },
      contentAlignment = Alignment.Center,
  ) {
    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(dims.icon))
  }
}

/**
 * The dock from WearWise, Go, Vandana and Dueline: a compact glass pill of icon tabs, sized to its
 * contents, where the current tab opens into a crimson pill with its name.
 */
@Composable
private fun Dock(nav: AppNav, backdrop: Backdrop) {
  Glass(backdrop, corner = 999.dp, modifier = Modifier.padding(horizontal = 8.dp)) {
    // An unusually narrow window scrolls the tabs rather than shrinking their targets.
    Row(
        Modifier.padding(6.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      for (tab in PrimaryTabs) DockItem(tab, selected = nav.tab == tab) { nav.tab = tab }
    }
  }
}

@Composable
private fun DockItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  val car = LocalScreen.current.isCar
  val fill by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, tween(220, easing = Glide), label = "dock fill")
  val tint by animateColorAsState(if (selected) Palette.OnAccent else Palette.Text2, tween(200), label = "dock tint")
  Row(
      Modifier.height(dims.touch).defaultMinSize(minWidth = dims.touch).clip(CircleShape).background(fill)
          .clickable(role = Role.Tab, onClick = onClick)
          .semantics { this.selected = selected }
          .padding(horizontal = if (car) 22.dp else 13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
  ) {
    // The visible name speaks for the current tab; the others are named by their icon.
    Icon(tab.icon, contentDescription = if (selected) null else tab.label, tint = tint, modifier = Modifier.size(dims.icon))
    AnimatedVisibility(
        selected,
        enter = expandHorizontally(tween(240, 40, Glide), expandFrom = Alignment.Start) + fadeIn(tween(160, 60)),
        exit = shrinkHorizontally(tween(200, easing = Glide), shrinkTowards = Alignment.Start) + fadeOut(tween(120)),
    ) {
      Text(
          tab.label,
          style = (if (car) type.button else type.button.copy(fontSize = 14.sp)).copy(color = Palette.OnAccent, fontWeight = FontWeight.SemiBold),
          maxLines = 1,
          modifier = Modifier.padding(start = if (car) 10.dp else 8.dp),
      )
    }
  }
}

/** What's playing, a glass bar above the dock, when the player screen is closed. */
@Composable
private fun MiniPlayer(backdrop: Backdrop, onOpen: () -> Unit) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val type = LocalType.current
  val now by hub.now.collectAsState()
  val ui by hub.ui.collectAsState()
  val np = now ?: return
  val car = LocalScreen.current.isCar
  Glass(backdrop, corner = dims.sheetCorner, modifier = Modifier.padding(horizontal = dims.gutter).widthIn(max = if (car) 760.dp else 560.dp).fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(dims.sheetCorner)).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Artwork(np.video, Modifier.height(dims.touch - 4.dp).aspectRatio(16f / 9f))
      Spacer(Modifier.width(dims.gap))
      Column(Modifier.weight(1f)) {
        Text(np.video.title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val status =
            when {
              np.isYouTube && !ui.playing -> "YouTube · open to play"
              ui.playing -> "Playing · ${formatClock(ui.positionMs)}"
              else -> "Paused · ${formatClock(ui.positionMs)}"
            }
        Text(status, style = type.meta, maxLines = 1)
      }
      if (!np.isYouTube) IconAction(if (ui.playing) Icons.Pause else Icons.Play, if (ui.playing) "Pause" else "Play", onClick = { hub.togglePlay() })
      IconAction(Icons.Close, "Stop and close", onClick = { hub.close() }, tint = Palette.Text2)
    }
  }
}

@Composable
private fun MessageBar(modifier: Modifier, visible: Boolean) {
  val type = LocalType.current
  val dims = LocalDims.current
  var message by remember { mutableStateOf<String?>(null) }
  val showMessages by rememberUpdatedState(visible)
  // Keep collecting while a YouTube player is shown, but discard rather than defer those toasts.
  LaunchedEffect(Unit) { AppMessages.flow.collect { message = it.takeIf { showMessages } } }
  LaunchedEffect(visible) { if (!visible) message = null }
  LaunchedEffect(message) {
    if (message != null) {
      delay(3_500)
      message = null
    }
  }
  // Remove an existing toast immediately; even its exit fade must not overlay a live embed.
  if (!visible) return
  AnimatedVisibility(message != null, modifier, enter = fadeIn(), exit = fadeOut()) {
    Text(
        message.orEmpty(),
        style = type.body,
        modifier = Modifier.padding(horizontal = dims.gutter).panel(dims.sheetCorner).background(Palette.SurfaceTop).padding(horizontal = dims.gap, vertical = 14.dp),
    )
  }
}

/** Covers everything while Android Automotive OS says the car is moving. No buttons on purpose. */
@Composable
private fun DrivingCover() {
  val hub = AppGraph.hub
  val type = LocalType.current
  val dims = LocalDims.current
  val now by hub.now.collectAsState()
  val ui by hub.ui.collectAsState()
  Box(Modifier.fillMaxSize().background(Palette.Canvas).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Car, null, tint = Palette.Text2, modifier = Modifier.size(dims.iconLarge * 1.5f))
      Spacer(Modifier.height(dims.gap))
      Text("Video is paused while driving", style = type.hero)
      Spacer(Modifier.height(8.dp))
      Text("DwellPlay is available when the car is parked.", style = type.body.copy(color = Palette.Text2))
      if (now != null && ui.positionMs > 0) {
        Spacer(Modifier.height(8.dp))
        Text("Your place is saved at ${formatClock(ui.positionMs)}.", style = type.body.copy(color = Palette.Text2))
      }
      Spacer(Modifier.height(8.dp))
      Text("No action is needed.", style = type.meta)
    }
  }
}
