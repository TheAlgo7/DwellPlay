package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.car.Device
import com.thealgothrim.dwellplay.core.model.Source
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.sources.PhoneVideos
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette

@Composable
fun SourcesScreen(actions: AppActions) {
  val library = AppGraph.library
  val dims = LocalDims.current
  val type = LocalType.current
  val sources by library.sources.collectAsState()
  val phone by library.phone.collectAsState()
  val saved by library.saved.collectAsState()
  val shelves by library.shelves.collectAsState()
  val folders by library.folders.collectAsState()
  val access by library.phoneAccess.collectAsState()
  val prefs by AppGraph.settings.prefs.collectAsState()

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutterStart(), end = dims.gutter, top = dims.gutter, bottom = dims.gutter + LocalChromeInsets.current.bottom), verticalArrangement = Arrangement.spacedBy(dims.gap / 2)) {
    item { Text("Your sources", style = type.hero) }
    item {
      SourceLine(
          Icons.Folder,
          PhoneVideos.NAME,
          when (access) {
            PhoneVideos.Access.ALL -> "${phone.size} videos in ${PhoneVideos.FOLDER_LABEL}"
            PhoneVideos.Access.SOME -> "${phone.size} videos you picked, from ${PhoneVideos.FOLDER_LABEL}"
            PhoneVideos.Access.NONE -> "Not allowed yet"
          },
      ) {
        if (access == PhoneVideos.Access.ALL) ActionButton("Refresh", { library.refreshPhone() }, icon = Icons.Refresh)
        else ActionButton("Allow", actions.askPhoneAccess, primary = true)
      }
    }
    item {
      val count = saved.count { it.type == SourceType.YOUTUBE }
      val search = if (prefs.effectiveYouTubeKey != null) "search on" else "search needs an API key"
      SourceLine(Icons.Embed, "YouTube", "$count saved, $search") { ActionButton("Add link", { actions.dialog(AppDialog.AddYouTube) }, icon = Icons.Add) }
    }
    item {
      SourceLine(Icons.Link, "Links", "${saved.count { it.type == SourceType.STREAM }} saved") {
        ActionButton("Add link", { actions.dialog(AppDialog.AddStream) }, icon = Icons.Add)
      }
    }
    for (s in sources) {
      item(key = s.id) {
        when (s.type) {
          SourceType.FOLDER ->
              SourceLine(Icons.Folder, s.name, "${folders[s.id]?.size ?: 0} videos") {
                ActionButton("Refresh", { library.refresh(s) }, icon = Icons.Refresh)
                Spacer(Modifier.width(8.dp))
                RemoveButton(s)
              }
          SourceType.JELLYFIN -> {
            val shelf = shelves[s.id]
            val status =
                when {
                  shelf?.signInAgain == true -> "Sign in again"
                  shelf?.error != null -> shelf.error
                  shelf?.loading == true -> "Connecting"
                  else -> "Signed in as ${s.server?.userName}"
                }
            SourceLine(Icons.Server, s.name, status) {
              if (shelf?.signInAgain == true) ActionButton("Sign in", { actions.dialog(AppDialog.Jellyfin(s.server?.baseUrl)) }, primary = true)
              else ActionButton("Refresh", { library.refresh(s) }, icon = Icons.Refresh)
              Spacer(Modifier.width(8.dp))
              RemoveButton(s)
            }
          }
          else -> Unit
        }
      }
    }
    item { Spacer(Modifier.size(dims.gap)) }
    item { Text("Add a source", style = type.heading) }
    item {
      AddLine(Icons.Folder, "A folder or USB drive", "Pick a folder with the system file picker. Only that folder is read.") { ActionButton("Choose", actions.pickFolder) }
    }
    item {
      AddLine(Icons.Server, "A Jellyfin server", "Your own movies, shows and music videos. The server converts anything the car can't play.") {
        ActionButton("Connect", { actions.dialog(AppDialog.Jellyfin()) })
      }
    }
    item {
      AddLine(Icons.Embed, "A YouTube video or playlist", "Or share it straight from the YouTube app. It plays in YouTube's own player, ads and all.") {
        ActionButton("Add", { actions.dialog(AppDialog.AddYouTube) })
      }
    }
    item {
      AddLine(Icons.Link, "A stream or file link", "HLS (.m3u8), DASH (.mpd) or a video file you own or have rights to.") { ActionButton("Add", { actions.dialog(AppDialog.AddStream) }) }
    }
    item {
      Text(
          "Netflix, Prime Video and other subscription services play only in their own apps, so DwellPlay doesn't try to play them.",
          style = type.meta,
          modifier = Modifier.padding(top = dims.gap),
      )
    }
  }
}

@Composable
private fun RemoveButton(s: Source) {
  val library = AppGraph.library
  IconAction(Icons.Delete, "Remove ${s.name}", onClick = {
    library.removeSource(s.id)
    AppMessages.say("Removed ${s.name}")
  }, tint = Palette.Text2)
}

@Composable
private fun SourceLine(icon: ImageVector, title: String, status: String, actions: @Composable () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.fillMaxWidth().panel(dims.corner).defaultMinSize(minHeight = dims.touch + 16.dp).padding(horizontal = dims.gap, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(dims.icon))
    Spacer(Modifier.width(dims.gap / 2))
    Column(Modifier.weight(1f)) {
      Text(title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(status, style = type.meta, maxLines = 2)
    }
    Row(verticalAlignment = Alignment.CenterVertically) { actions() }
  }
}

@Composable
private fun AddLine(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Palette.Text2, modifier = Modifier.size(dims.icon))
    Spacer(Modifier.width(dims.gap / 2))
    Column(Modifier.weight(1f)) {
      Text(title, style = type.title)
      Text(body, style = type.meta)
    }
    Spacer(Modifier.width(dims.gap / 2))
    action()
  }
}
