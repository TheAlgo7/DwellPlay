package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.car.Device
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.sources.PhoneVideos
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalHostCorners
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import com.thealgothrim.dwellplay.ui.theme.isCar

@Composable
fun HomeScreen(screen: Screen, nav: AppNav, actions: AppActions, railNavigation: Boolean = false) {
  val library = AppGraph.library
  val dims = LocalDims.current
  val phone by library.phone.collectAsState()
  val saved by library.saved.collectAsState()
  val shelves by library.shelves.collectAsState()
  val folders by library.folders.collectAsState()
  val sources by library.sources.collectAsState()
  val continueWatching by library.continueWatching.collectAsState()
  val data by library.data.collectAsState()
  val access by library.phoneAccess.collectAsState()
  val progressOf = { v: Video -> ResumeRules.fraction(data.progress[v.id]) }
  val manage = { v: Video -> actions.dialog(AppDialog.Manage(v)) }

  val youtube = saved.filter { it.type == SourceType.YOUTUBE }
  val links = saved.filter { it.type == SourceType.STREAM }
  val nothingYet = phone.isEmpty() && saved.isEmpty() && sources.isEmpty() && continueWatching.isEmpty()

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = dims.gap, bottom = dims.section * 2), verticalArrangement = Arrangement.spacedBy(dims.section + if (screen.isCar) 0.dp else 8.dp)) {
    item { HomeHeader(screen, showSettings = !screen.isCar || !railNavigation, onSettings = { actions.goTo(Tab.SETTINGS) }) }
    if (nothingYet) item { Welcome(access != PhoneVideos.Access.NONE, actions) }
    // Rows only exist when they have something in them, so empty ones leave no gaps.
    if (continueWatching.isNotEmpty()) item { CardRow("Continue watching", continueWatching, progressOf, actions.open, manage) }
    if (youtube.isNotEmpty()) item { CardRow("YouTube", youtube, progressOf, actions.open, manage) }
    if (phone.isNotEmpty()) item {
      CardRow(
          "On ${Device.here}",
          phone.take(24),
          progressOf,
          actions.open,
          action = {
            if (phone.size > 24) {
              ActionButton("See all ${phone.size}", onClick = {
                nav.librarySource = PhoneVideos.SOURCE_ID
                nav.browse.clear()
                actions.goTo(Tab.LIBRARY)
              })
            }
          },
      )
    }
    for (s in sources) {
      when (s.type) {
        SourceType.JELLYFIN -> {
          val shelf = shelves[s.id]
          if (!shelf?.resume.isNullOrEmpty()) item(key = "resume-${s.id}") { CardRow("Continue on ${s.name}", shelf.resume, progressOf, actions.open) }
          if (!shelf?.latest.isNullOrEmpty()) item(key = "latest-${s.id}") { CardRow("New on ${s.name}", shelf.latest, progressOf, actions.open) }
          if (shelf?.error != null) {
            item(key = "err-${s.id}") {
              InlineNotice(shelf.error, if (shelf.signInAgain) "Sign in" else "Try again") {
                if (shelf.signInAgain) actions.dialog(AppDialog.Jellyfin(s.server?.baseUrl)) else library.refresh(s)
              }
            }
          }
        }
        SourceType.FOLDER ->
            if (!folders[s.id].isNullOrEmpty()) item(key = "folder-${s.id}") { CardRow(s.name, folders[s.id].orEmpty().take(24), progressOf, actions.open) }
        else -> Unit
      }
    }
    if (links.isNotEmpty()) item { CardRow("Links", links, progressOf, actions.open, manage) }
    if (!nothingYet && access == PhoneVideos.Access.NONE) {
      item { InlineNotice("Show the videos saved on ${Device.here} here too.", "Allow", actions.askPhoneAccess) }
    }
  }
}

@Composable
private fun HomeHeader(screen: Screen, showSettings: Boolean, onSettings: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = dims.gutter)) {
    val statusInRow = screen.isCar && maxWidth >= 720.dp
    Column {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Wordmark()
          if (!screen.isCar) {
            Spacer(Modifier.height(4.dp))
            Text("Your media, together.", style = type.meta)
          }
        }
        if (statusInRow) ParkedStatus()
        if (showSettings) {
          Spacer(Modifier.width(dims.gap / 2))
          IconAction(Icons.Settings, "Settings", onClick = onSettings, modifier = Modifier.chrome(dims.touch / 2), tint = Palette.Text2)
        }
        // Android Auto's Exit button owns the top-right corner.
        if (statusInRow) Spacer(Modifier.width(LocalHostCorners.current.end))
      }
      if (screen.isCar && !statusInRow) {
        Spacer(Modifier.height(dims.gap / 2))
        ParkedStatus()
      }
    }
  }
}

@Composable
private fun ParkedStatus() {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(verticalAlignment = Alignment.CenterVertically) {
    // The state is always expressed in words and an icon, rather than color alone.
    Icon(Icons.Parked, null, tint = Palette.Text2, modifier = Modifier.size(dims.icon))
    Spacer(Modifier.width(8.dp))
    Text("Parked, video on", style = type.body)
  }
}

/** First run: what DwellPlay is and the three ways to fill it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(phoneAllowed: Boolean, actions: AppActions) {
  val dims = LocalDims.current
  val type = LocalType.current
  Column(Modifier.padding(horizontal = dims.gutter).fillMaxWidth().panel(dims.sheetCorner).padding(dims.gap)) {
    Text("Your parked-screen media hub", style = type.hero)
    Spacer(Modifier.height(8.dp))
    Text(
        if (Device.isCar) "Videos saved in this car, YouTube, your own server and stream links, on the big screen whenever you're parked. When you drive, your own videos can keep playing as sound."
        else "Videos on this phone, YouTube, your own server and stream links, on the car's big screen whenever you're parked. When you drive, your own videos keep playing as sound.",
        style = type.body.copy(color = Palette.Text2),
    )
    Spacer(Modifier.height(dims.gap))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(dims.gap / 2), verticalArrangement = Arrangement.spacedBy(dims.gap / 2)) {
      if (!phoneAllowed) ActionButton(if (Device.isCar) "Show saved videos" else "Show phone videos", actions.askPhoneAccess, icon = Icons.Phone, primary = true)
      ActionButton("Add a YouTube link", { actions.dialog(AppDialog.AddYouTube) }, icon = Icons.Embed, primary = phoneAllowed)
      ActionButton("Connect Jellyfin", { actions.dialog(AppDialog.Jellyfin()) }, icon = Icons.Server)
    }
    Spacer(Modifier.height(dims.gap))
    if (!Device.isCar) Text("Tip: in the YouTube app, tap Share, then DwellPlay, to save a video for the car.", style = type.meta)
  }
}

@Composable
fun InlineNotice(text: String, action: String?, onAction: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.padding(horizontal = dims.gutter).fillMaxWidth().panel(dims.corner).padding(horizontal = dims.gap, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Info, null, tint = Palette.Warning, modifier = Modifier.size(dims.icon))
    Spacer(Modifier.width(12.dp))
    Text(text, style = type.body, modifier = Modifier.weight(1f))
    if (action != null) ActionButton(action, onAction)
  }
}
