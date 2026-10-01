package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.car.Device
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinAuthException
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.core.youtube.YOUTUBE_SOURCE_ID
import com.thealgothrim.dwellplay.sources.PhoneVideos
import com.thealgothrim.dwellplay.sources.STREAMS_SOURCE_ID
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalScreen
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import com.thealgothrim.dwellplay.ui.theme.isCar

private data class SourceEntry(val id: String, val name: String, val type: SourceType, val count: Int?)

@Composable
fun LibraryScreen(nav: AppNav, actions: AppActions) {
  val library = AppGraph.library
  val dims = LocalDims.current
  val sources by library.sources.collectAsState()
  val phone by library.phone.collectAsState()
  val saved by library.saved.collectAsState()
  val folders by library.folders.collectAsState()

  val entries =
      listOf(
          SourceEntry(PhoneVideos.SOURCE_ID, Device.label, SourceType.PHONE, phone.size),
          SourceEntry(YOUTUBE_SOURCE_ID, "YouTube", SourceType.YOUTUBE, saved.count { it.type == SourceType.YOUTUBE }),
          SourceEntry(STREAMS_SOURCE_ID, "Links", SourceType.STREAM, saved.count { it.type == SourceType.STREAM }),
      ) + sources.map { SourceEntry(it.id, it.name, it.type, folders[it.id]?.size) }
  val selected = entries.firstOrNull { it.id == nav.librarySource } ?: entries.first()
  val select = { id: String ->
    nav.librarySource = id
    nav.browse.clear()
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val width = maxWidth
    if (width >= 720.dp) {
      Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(if (width >= 1200.dp) 340.dp else 260.dp).fillMaxHeight(), contentPadding = PaddingValues(dims.gap / 2)) {
          items(entries, key = { it.id }) { e -> SourceRow(e, e.id == selected.id) { select(e.id) } }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { SourceContent(selected, nav, actions) }
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(entries, key = { it.id }) { e -> SourceChip(e, e.id == selected.id) { select(e.id) } }
        }
        Box(Modifier.weight(1f)) { SourceContent(selected, nav, actions) }
      }
    }
  }
}

@Composable
private fun SourceRow(e: SourceEntry, selected: Boolean, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.fillMaxWidth()
          .defaultMinSize(minHeight = dims.touch)
          .clip(RoundedCornerShape(dims.corner))
          .background(if (selected) Palette.AccentSoft else Palette.Canvas)
          .clickable(role = Role.Tab, onClick = onClick)
          .padding(horizontal = dims.gap / 2),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(e.type.icon(), null, tint = if (selected) Palette.Accent else Palette.Text2, modifier = Modifier.size(dims.icon))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(e.name, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (e.count != null) Text(if (e.count == 1) "1 video" else "${e.count} videos", style = type.meta)
    }
  }
}

@Composable
private fun SourceChip(e: SourceEntry, selected: Boolean, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.clip(RoundedCornerShape(dims.corner))
          .background(if (selected) Palette.AccentSoft else Palette.Surface)
          .clickable(role = Role.Tab, onClick = onClick)
          .defaultMinSize(minHeight = dims.touch - 8.dp)
          .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(e.type.icon(), null, tint = if (selected) Palette.Accent else Palette.Text2, modifier = Modifier.size(dims.iconSmall))
    Spacer(Modifier.width(6.dp))
    Text(e.name, style = type.button.copy(color = if (selected) Palette.Accent else Palette.Text), maxLines = 1)
  }
}

@Composable
private fun SourceContent(entry: SourceEntry, nav: AppNav, actions: AppActions) {
  val library = AppGraph.library
  val saved by library.saved.collectAsState()
  val folders by library.folders.collectAsState()
  val phone by library.phone.collectAsState()
  val access by library.phoneAccess.collectAsState()
  val manage = { v: Video -> actions.dialog(AppDialog.Manage(v)) }

  when (entry.type) {
    SourceType.PHONE ->
        if (access == PhoneVideos.Access.NONE) {
          MessagePanel(Icons.Phone, "Show the videos on ${Device.here}", "DwellPlay only reads your videos to list and play them. Nothing leaves ${Device.here}.", Modifier.fillMaxSize()) {
            ActionButton("Allow", actions.askPhoneAccess, primary = true)
          }
        } else {
          PhoneLibrary(phone, access, actions)
        }
    SourceType.YOUTUBE -> {
      val list = saved.filter { it.type == SourceType.YOUTUBE }
      if (list.isEmpty()) {
        MessagePanel(Icons.Embed, "No YouTube videos yet", "Share a video or playlist from the YouTube app to DwellPlay, or paste a link.", Modifier.fillMaxSize()) {
          ActionButton("Add a link", { actions.dialog(AppDialog.AddYouTube) }, icon = Icons.Add, primary = true)
        }
      } else {
        VideoGrid(list, actions.open, manage, header = { ActionButton("Add a link", { actions.dialog(AppDialog.AddYouTube) }, icon = Icons.Add) })
      }
    }
    SourceType.STREAM -> {
      val list = saved.filter { it.type == SourceType.STREAM }
      if (list.isEmpty()) {
        MessagePanel(Icons.Link, "No links yet", "Add an HLS, DASH or video file link you own or have rights to.", Modifier.fillMaxSize()) {
          ActionButton("Add a link", { actions.dialog(AppDialog.AddStream) }, icon = Icons.Add, primary = true)
        }
      } else {
        VideoGrid(list, actions.open, manage, header = { ActionButton("Add a link", { actions.dialog(AppDialog.AddStream) }, icon = Icons.Add) })
      }
    }
    SourceType.FOLDER -> {
      val list = folders[entry.id].orEmpty()
      if (list.isEmpty()) {
        MessagePanel(Icons.Folder, "No videos found", "Nothing playable in ${entry.name} yet. If it's a USB drive, check it's plugged in.", Modifier.fillMaxSize()) {
          ActionButton("Look again", { library.sources.value.firstOrNull { it.id == entry.id }?.let(library::refresh) }, icon = Icons.Refresh)
        }
      } else {
        VideoGrid(list, actions.open)
      }
    }
    SourceType.JELLYFIN -> ServerBrowser(entry, nav, actions)
  }
}

@Composable
private fun PhoneLibrary(phone: List<Video>, access: PhoneVideos.Access, actions: AppActions) {
  val dims = LocalDims.current
  var folder by remember { mutableStateOf<String?>(null) }
  val folders = remember(phone) { phone.mapNotNull { it.subtitle }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
  val shown = if (folder == null) phone else phone.filter { it.subtitle == folder }
  Column(Modifier.fillMaxSize()) {
    if (access == PhoneVideos.Access.SOME) InlineNotice("Showing only the videos you picked.", "Allow all", actions.askPhoneAccess)
    if (folders.size > 1) {
      LazyRow(contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip("All", folder == null) { folder = null } }
        items(folders) { f -> FilterChip(f, folder == f) { folder = f } }
      }
    }
    if (shown.isEmpty()) {
      MessagePanel(Icons.Phone, "No videos on ${Device.here} yet", "Downloaded music videos, recordings and clips show up here.", Modifier.fillMaxSize())
    } else {
      VideoGrid(shown, actions.open)
    }
  }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Box(
      Modifier.clip(RoundedCornerShape(dims.corner))
          .background(if (selected) Palette.AccentSoft else Palette.Surface)
          .clickable(role = Role.Tab, onClick = onClick)
          .defaultMinSize(minHeight = dims.touch - 8.dp)
          .padding(horizontal = 14.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, style = type.button.copy(color = if (selected) Palette.Accent else Palette.Text), maxLines = 1)
  }
}

@Composable
fun VideoGrid(videos: List<Video>, open: (Video, List<Video>) -> Unit, onLongClick: ((Video) -> Unit)? = null, header: (@Composable () -> Unit)? = null) {
  val dims = LocalDims.current
  val data by AppGraph.library.data.collectAsState()
  // Two columns on a phone, car-sized cards in a car.
  val cell = if (LocalScreen.current.isCar) dims.cardWidth else 164.dp
  LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = cell),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, top = dims.gap / 2, bottom = dims.section * 2),
      horizontalArrangement = Arrangement.spacedBy(dims.gap),
      verticalArrangement = Arrangement.spacedBy(dims.gap),
  ) {
    if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Row { header() } }
    items(videos, key = { it.id }) { v ->
      MediaCard(v, ResumeRules.fraction(data.progress[v.id]), onClick = { open(v, videos) }, width = null, onLongClick = onLongClick?.let { { it(v) } }, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun ServerBrowser(entry: SourceEntry, nav: AppNav, actions: AppActions) {
  val library = AppGraph.library
  val dims = LocalDims.current
  val type = LocalType.current
  val target = nav.browse.lastOrNull { it.sourceId == entry.id } ?: BrowseTarget(entry.id, null, entry.name)
  var items by remember(target) { mutableStateOf<List<Video>?>(null) }
  var error by remember(target) { mutableStateOf<String?>(null) }
  var signIn by remember(target) { mutableStateOf(false) }
  var attempt by remember(target) { mutableStateOf(0) }
  LaunchedEffect(target, attempt) {
    items = null
    error = null
    try {
      items = library.browseServer(target.sourceId, target.parentId)
    } catch (e: JellyfinAuthException) {
      signIn = true
      error = "Sign in to ${entry.name} again."
    } catch (e: Exception) {
      error = "Can't reach ${entry.name}. Check the connection."
    }
  }
  Column(Modifier.fillMaxSize()) {
    if (target.parentId != null) {
      Row(Modifier.fillMaxWidth().padding(horizontal = dims.gutter / 2), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Back, "Back", onClick = { nav.browse.removeAt(nav.browse.lastIndex) })
        Text(target.title, style = type.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    val list = items
    when {
      error != null ->
          MessagePanel(Icons.Error, error!!, null, Modifier.fillMaxSize(), iconTint = Palette.Warning) {
            if (signIn) ActionButton("Sign in", { actions.dialog(AppDialog.Jellyfin(library.sources.value.firstOrNull { it.id == entry.id }?.server?.baseUrl)) }, primary = true)
            else ActionButton("Try again", { attempt++ }, icon = Icons.Refresh)
          }
      list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
      list.isEmpty() -> MessagePanel(Icons.Server, "Nothing here", null, Modifier.fillMaxSize())
      else ->
          VideoGrid(list, open = { v, all ->
            if (v.isFolder) nav.browse.add(BrowseTarget(entry.id, (v.ref as com.thealgothrim.dwellplay.core.model.MediaRef.Jellyfin).itemId, v.title))
            else actions.open(v, all.filter { !it.isFolder })
          })
    }
  }
}
