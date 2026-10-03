package com.thealgothrim.dwellplay.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.AppGraph
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

@Composable
fun DwellApp(screen: Screen, restricted: Boolean, nav: AppNav) {
  val hub = AppGraph.hub
  val library = AppGraph.library
  val now by hub.now.collectAsState()

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
      remember(nav) {
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
              }
            },
            askPhoneAccess = { phonePermission.launch(PhoneVideos.permissions()) },
            pickFolder = { folderPicker.launch(null) },
            dialog = { nav.dialog = it },
            goTo = { nav.tab = it; nav.playerOpen = false },
        )
      }

  BackHandler(enabled = nav.playerOpen || nav.browse.isNotEmpty() || nav.tab != Tab.HOME) {
    when {
      nav.playerOpen -> {
        hub.leftPlayerScreen()
        nav.playerOpen = false
      }
      nav.tab == Tab.LIBRARY && nav.browse.isNotEmpty() -> nav.browse.removeAt(nav.browse.lastIndex)
      else -> nav.tab = Tab.HOME
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Canvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
    // A portrait car needs its width for content. Only a roomy landscape window gets the rail.
    // Short windows also use the dock, so the pinned Settings action never squeezes a tab's target.
    val roomForRail = maxHeight >= if (screen.isCar) 360.dp else 240.dp
    val wide = roomForRail && if (screen.isCar) maxWidth >= 840.dp && maxWidth > maxHeight
        else maxWidth >= 840.dp || (maxWidth > maxHeight && maxWidth >= 600.dp)
    val current = now
    if (nav.playerOpen && current != null) {
      PlayerScreen(
          screen = screen,
          wide = wide,
          onClose = {
            hub.leftPlayerScreen()
            nav.playerOpen = false
          },
          actions = actions,
      )
    } else {
      if (wide) {
        Row(Modifier.fillMaxSize()) {
          NavRail(nav)
          Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f)) { TabContent(nav, screen, actions, railNavigation = true) }
            if (current != null) MiniPlayer(onOpen = { nav.playerOpen = true })
          }
        }
      } else {
        Column(Modifier.fillMaxSize()) {
          // On compact projected windows, reserve both host corners for every browsing screen.
          Spacer(Modifier.height(LocalHostCorners.current.height))
          Box(Modifier.weight(1f)) { TabContent(nav, screen, actions, railNavigation = false) }
          if (current != null) MiniPlayer(onOpen = { nav.playerOpen = true })
          BottomBar(nav)
        }
      }
    }

    // Dialog has its own Android window, so a Compose driving cover cannot hide it.
    // Keep the pending dialog in navigation state and restore it only once restrictions clear.
    if (!restricted) nav.dialog?.let { d -> DialogHost(d, onDismiss = { nav.dialog = null }, actions = actions) }

    MessageBar(
        Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide || nav.playerOpen) 24.dp else LocalDims.current.touch + 44.dp),
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

@Composable
private fun NavRail(nav: AppNav) {
  val dims = LocalDims.current
  Column(
      Modifier.width(dims.rail).fillMaxHeight().padding(horizontal = 8.dp, vertical = 12.dp)
          .chrome(dims.sheetCorner).padding(horizontal = 4.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Android Auto puts its own back arrow in this corner: the tabs start below it.
    Spacer(Modifier.height(LocalHostCorners.current.height))
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      for (tab in PrimaryTabs) NavItem(tab, selected = nav.tab == tab) { nav.tab = tab }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(Palette.Hairline))
    Spacer(Modifier.height(4.dp))
    NavItem(Tab.SETTINGS, selected = nav.tab == Tab.SETTINGS) { nav.tab = Tab.SETTINGS }
  }
}

@Composable
private fun BottomBar(nav: AppNav) {
  val dims = LocalDims.current
  val car = LocalScreen.current.isCar
  BoxWithConstraints(
      Modifier.fillMaxWidth().padding(horizontal = dims.gutter, vertical = if (car) 12.dp else 8.dp),
      contentAlignment = Alignment.Center,
  ) {
    val minimumWidth = dims.touch * PrimaryTabs.size + 4.dp * (PrimaryTabs.size - 1) + 12.dp
    val scroll = maxWidth < minimumWidth
    Row(
        Modifier.widthIn(max = if (car) 720.dp else 560.dp).fillMaxWidth()
            .chrome(dims.sheetCorner + 4.dp).padding(6.dp)
            .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      for (tab in PrimaryTabs) {
        // Fixed targets in an unusually narrow window; ordinary docks keep all four evenly spaced.
        // Text semantics retain the complete destination name even if a large font is ellipsized.
        val itemModifier = if (scroll) Modifier.width(dims.touch) else Modifier.weight(1f)
        NavItem(tab, selected = nav.tab == tab, modifier = itemModifier) { nav.tab = tab }
      }
    }
  }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  val car = LocalScreen.current.isCar
  val tint = if (selected) Palette.Text else Palette.Text2
  Column(
      modifier
          .fillMaxWidth()
          .defaultMinSize(minWidth = dims.touch, minHeight = dims.touch)
          .clip(RoundedCornerShape(dims.corner + 4.dp))
          .background(if (selected) Palette.Surface else Color.Transparent)
          .clickable(role = Role.Tab, onClick = onClick)
          .semantics { this.selected = selected }
          .padding(vertical = 4.dp, horizontal = 2.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(dims.icon))
    Spacer(Modifier.height(2.dp))
    Text(tab.label, style = (if (car) type.meta else type.small).copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(3.dp))
    Box(Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Palette.Accent else Color.Transparent))
  }
}

/** What's playing, one line above the navigation, when the player screen is closed. */
@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
  val hub = AppGraph.hub
  val dims = LocalDims.current
  val type = LocalType.current
  val now by hub.now.collectAsState()
  val ui by hub.ui.collectAsState()
  val np = now ?: return
  Row(
      Modifier.padding(horizontal = dims.gutter, vertical = 4.dp).fillMaxWidth()
          .chrome(dims.sheetCorner).defaultMinSize(minHeight = dims.touch)
          .clickable(onClick = onOpen).padding(horizontal = dims.gap / 2, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Artwork(np.video, Modifier.height(dims.touch - 12.dp).aspectRatio(16f / 9f))
    Spacer(Modifier.width(dims.gap / 2))
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
    IconAction(Icons.Close, "Stop and close", onClick = { hub.close() })
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
