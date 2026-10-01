package com.thealgothrim.dwellplay.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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

/** A Jellyfin folder being browsed in the library. */
data class BrowseTarget(val sourceId: String, val parentId: String?, val title: String)

sealed interface AppDialog {
  data object AddYouTube : AppDialog
  data object AddStream : AppDialog
  data class Jellyfin(val address: String? = null) : AppDialog
  data class Manage(val video: Video) : AppDialog
}

/** Where the user is. Plain state rather than a navigation library: five tabs, a player, a drill-down. */
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
    val wide = screen.isCar || maxWidth >= 840.dp || (maxWidth > maxHeight && maxWidth >= 600.dp)
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
            Box(Modifier.weight(1f)) { TabContent(nav, screen, actions) }
            if (current != null) MiniPlayer(onOpen = { nav.playerOpen = true })
          }
        }
      } else {
        Column(Modifier.fillMaxSize()) {
          Box(Modifier.weight(1f)) { TabContent(nav, screen, actions) }
          if (current != null) MiniPlayer(onOpen = { nav.playerOpen = true })
          BottomBar(nav)
        }
      }
    }

    nav.dialog?.let { d -> DialogHost(d, onDismiss = { nav.dialog = null }, actions = actions) }

    MessageBar(Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 24.dp else 96.dp))

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
private fun TabContent(nav: AppNav, screen: Screen, actions: AppActions) {
  when (nav.tab) {
    Tab.HOME -> HomeScreen(screen, nav, actions)
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
      Modifier.width(dims.rail).fillMaxHeight().background(Palette.Surface).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // Android Auto puts its own back arrow in this corner: the tabs start below it.
    Spacer(Modifier.height(LocalHostCorners.current.height))
    for (tab in Tab.entries) NavItem(tab, selected = nav.tab == tab, vertical = true) { nav.tab = tab }
  }
}

@Composable
private fun BottomBar(nav: AppNav) {
  Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
    for (tab in Tab.entries) NavItem(tab, selected = nav.tab == tab, vertical = true, modifier = Modifier.weight(1f)) { nav.tab = tab }
  }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, vertical: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  val tint = if (selected) Palette.Accent else Palette.Text2
  Column(
      modifier
          .padding(horizontal = 6.dp)
          .clip(RoundedCornerShape(dims.corner * 2))
          .background(if (selected) Palette.AccentSoft else Palette.Surface)
          .clickable(role = Role.Tab, onClick = onClick)
          .padding(vertical = 6.dp, horizontal = 4.dp)
          .fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(dims.icon))
    if (vertical) Text(tab.label, style = type.small.copy(color = tint), maxLines = 1)
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
      Modifier.fillMaxWidth().background(Palette.SurfaceHigh).clickable(onClick = onOpen).padding(horizontal = dims.gutter / 2, vertical = 6.dp),
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
private fun MessageBar(modifier: Modifier) {
  val type = LocalType.current
  val dims = LocalDims.current
  var message by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) { AppMessages.flow.collect { message = it } }
  LaunchedEffect(message) {
    if (message != null) {
      delay(3_500)
      message = null
    }
  }
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
