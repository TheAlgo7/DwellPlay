package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette

/**
 * Audio language and subtitles for the playing file. A pick applies at once and the list stays
 * open, so audio and subtitles can both be set before going back to the film. The audio language
 * picked here is tried first in the next file too.
 */
@Composable
fun TracksDialog(onDismiss: () -> Unit) {
  val hub = AppGraph.hub
  val ui by hub.ui.collectAsState()
  val prefs by AppGraph.settings.prefs.collectAsState()
  val dims = LocalDims.current
  val type = LocalType.current
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(dims.gutter), contentAlignment = Alignment.Center) {
      // A wide, short screen (a car) puts Audio and Subtitles side by side, each scrolling on its
      // own, so a film with six subtitle tracks never pushes Done off the screen.
      val sideBySide = maxWidth > maxHeight * 1.4f && ui.audioTracks.isNotEmpty() && ui.textTracks.isNotEmpty()
      Column(
          Modifier.widthIn(max = if (sideBySide) 960.dp else 640.dp).fillMaxWidth().heightIn(max = maxHeight)
              .panel(dims.sheetCorner).padding(dims.gap),
      ) {
        val audio: @Composable (Modifier) -> Unit = { m ->
          Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Audio", style = type.heading, modifier = Modifier.padding(bottom = 4.dp))
            ui.audioTracks.forEach { t -> Choice(t.label, t.selected) { hub.selectAudio(t.key) } }
          }
        }
        val subtitles: @Composable (Modifier) -> Unit = { m ->
          Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Subtitles", style = type.heading, modifier = Modifier.padding(bottom = 4.dp))
            val anyOn = prefs.captions && ui.textTracks.any { it.selected }
            Choice("Off", !anyOn) { hub.selectText(null) }
            ui.textTracks.forEach { t -> Choice(t.label, prefs.captions && t.selected) { hub.selectText(t.key) } }
          }
        }
        if (sideBySide) {
          Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(dims.gap)) {
            audio(Modifier.weight(1f))
            subtitles(Modifier.weight(1f))
          }
        } else {
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (ui.audioTracks.isNotEmpty()) audio(Modifier)
            if (ui.audioTracks.isNotEmpty() && ui.textTracks.isNotEmpty()) Spacer(Modifier.height(dims.gap))
            if (ui.textTracks.isNotEmpty()) subtitles(Modifier)
          }
        }
        Row(Modifier.fillMaxWidth().padding(top = dims.gap / 2), horizontalArrangement = Arrangement.End) {
          ActionButton("Done", onDismiss, primary = true)
        }
      }
    }
  }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
  val dims = LocalDims.current
  val type = LocalType.current
  Row(
      Modifier.fillMaxWidth().heightIn(min = dims.touch)
          .clip(RoundedCornerShape(dims.corner))
          .clickable(role = Role.RadioButton, onClick = onClick)
          .semantics { this.selected = selected }
          .padding(horizontal = dims.gap / 2),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(dims.icon), contentAlignment = Alignment.Center) {
      if (selected) Icon(Icons.Check, null, tint = Palette.Accent, modifier = Modifier.size(dims.icon))
    }
    Spacer(Modifier.width(dims.gap / 2))
    Text(label, style = type.body.copy(color = if (selected) Palette.Text else Palette.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}
