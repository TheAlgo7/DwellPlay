package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.BuildConfig
import com.thealgothrim.dwellplay.car.CarScreens
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.data.PictureFit
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette

@Composable
fun SettingsScreen(screen: Screen) {
  val settings = AppGraph.settings
  val prefs by settings.prefs.collectAsState()
  val dims = LocalDims.current
  val type = LocalType.current
  val context = LocalContext.current
  var keyDraft by remember { mutableStateOf(prefs.youtubeKey) }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutterStart(), end = dims.gutter, top = dims.gutter, bottom = dims.gutter + LocalChromeInsets.current.bottom), verticalArrangement = Arrangement.spacedBy(dims.gap / 2)) {
    item { Text("Settings", style = type.hero) }

    item { Group("Playback") }
    item {
      Toggle(
          "Keep the sound when the picture hides",
          "When you start driving or leave the app, your own videos carry on as audio. YouTube always stops: its rules don't allow background play.",
          prefs.soundWhenHidden,
      ) { on -> settings.update { it.copy(soundWhenHidden = on) } }
    }
    item { Toggle("Play the next video automatically", "Handy for music videos.", prefs.autoplayNext) { on -> settings.update { it.copy(autoplayNext = on) } } }
    item { Toggle("Captions when available", null, prefs.captions) { on -> AppGraph.hub.setCaptions(on) } }
    item {
      Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = dims.touch), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Picture", style = type.title)
          Text("Fit shows the whole picture. Fill crops the edges to fill the screen.", style = type.meta)
        }
        Spacer(Modifier.width(dims.gap / 2))
        ActionButton("Fit", { settings.update { it.copy(fit = PictureFit.FIT) } }, primary = prefs.fit == PictureFit.FIT)
        Spacer(Modifier.width(8.dp))
        ActionButton("Fill", { settings.update { it.copy(fit = PictureFit.FILL) } }, primary = prefs.fit == PictureFit.FILL)
      }
    }

    item { Group("YouTube search") }
    item {
      Text(
          if (prefs.effectiveYouTubeKey != null) "Search is on." else "Shared links and playlists work without anything here. To search YouTube, add a free YouTube Data API v3 key from Google Cloud Console (about 100 searches a day).",
          style = type.body.copy(color = Palette.Text2),
      )
    }
    item { DwellField(keyDraft, "API key", { keyDraft = it.trim() }, password = true) }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Save key", {
          settings.update { it.copy(youtubeKey = keyDraft) }
          AppMessages.say(if (keyDraft.isBlank()) "Key removed" else "Key saved")
        }, primary = keyDraft != prefs.youtubeKey)
        if (prefs.youtubeKey.isNotBlank()) ActionButton("Remove key", {
          keyDraft = ""
          settings.update { it.copy(youtubeKey = "") }
        })
      }
    }

    item { Group("Car") }
    item {
      Info(
          "This screen",
          when (screen) {
            Screen.PHONE -> "Phone"
            Screen.ANDROID_AUTO -> "Android Auto car screen"
            Screen.AUTOMOTIVE -> "Android Automotive OS (Google built-in)"
          },
      )
    }
    item {
      Info(
          "How DwellPlay reaches the car",
          if (BuildConfig.CAR_ROUTE == "personal") "Personal build: Android Auto shows it as a parked app on Android 15 and newer phones, and closes it when the car moves."
          else "Store build: a video app for cars with Google built-in, and for Android Auto once Google opens video apps there.",
      )
    }
    item {
      Info(
          "Sound while driving",
          if (CarScreens.vehicleAllowsBackgroundAudio(context, screen)) "Allowed for your own videos (never YouTube)." else "This car doesn't offer it, so playback pauses when you drive.",
      )
    }

    item { Group("Library") }
    item {
      ActionButton("Clear watch history", {
        AppGraph.library.clearHistory()
        AppMessages.say("History cleared")
      }, icon = Icons.Delete)
    }
    item {
      Text("DwellPlay ${BuildConfig.VERSION_NAME} (${BuildConfig.CAR_ROUTE})", style = type.meta, modifier = Modifier.padding(top = dims.section))
    }
  }
}

@Composable
private fun Group(title: String) {
  val dims = LocalDims.current
  Text(title, style = LocalType.current.heading, modifier = Modifier.padding(top = dims.gap))
}

@Composable
private fun Toggle(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.fillMaxWidth().defaultMinSize(minHeight = dims.touch).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = type.title)
      if (body != null) Text(body, style = type.meta)
    }
    Spacer(Modifier.width(dims.gap / 2))
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.OnAccent, uncheckedTrackColor = Palette.SurfaceTop),
    )
  }
}

@Composable
private fun Info(title: String, value: String) {
  val type = LocalType.current
  Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Text(title, style = type.title)
    Text(value, style = type.meta)
  }
}
