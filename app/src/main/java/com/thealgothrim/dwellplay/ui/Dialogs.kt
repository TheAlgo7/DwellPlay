package com.thealgothrim.dwellplay.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinAuthException
import com.thealgothrim.dwellplay.core.links.Links
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.sources.AddResult
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import com.thealgothrim.dwellplay.youtube.openInYouTube
import kotlinx.coroutines.launch

@Composable
fun DialogHost(dialog: AppDialog, onDismiss: () -> Unit, actions: AppActions) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    val dims = LocalDims.current
    Column(
        Modifier.padding(dims.gutter)
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .panel(dims.sheetCorner)
            .verticalScroll(rememberScrollState())
            .padding(dims.gap),
        verticalArrangement = Arrangement.spacedBy(dims.gap / 2),
    ) {
      when (dialog) {
        AppDialog.AddYouTube -> AddLink(youtube = true, onDismiss)
        AppDialog.AddStream -> AddLink(youtube = false, onDismiss)
        is AppDialog.Jellyfin -> JellyfinSignIn(dialog.address, onDismiss)
        is AppDialog.Manage -> Manage(dialog.video, onDismiss, actions)
      }
    }
  }
}

@Composable
private fun AddLink(youtube: Boolean, onDismiss: () -> Unit) {
  val type = LocalType.current
  val dims = LocalDims.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var url by remember { mutableStateOf(clipboardLink(context).orEmpty()) }
  var name by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  Text(if (youtube) "Add a YouTube video or playlist" else "Add a stream or file link", style = type.heading)
  Text(
      if (youtube) "Paste a youtube.com or youtu.be link. Next time, share it from the YouTube app instead: tap Share, then DwellPlay."
      else "An HLS (.m3u8), DASH (.mpd) or video file link that you own or have rights to.",
      style = type.meta,
  )
  DwellField(url, if (youtube) "YouTube link" else "Link", { url = it; error = null }, keyboardType = KeyboardType.Uri)
  if (!youtube) DwellField(name, "Name (optional)", { name = it })
  error?.let { Text(it, style = type.body.copy(color = Palette.Error)) }
  ButtonRow {
    ActionButton("Paste", { clipboardText(context)?.let { url = it; error = null } })
    ActionButton("Cancel", onDismiss)
    ActionButton(if (busy) "Adding" else "Add", enabled = url.isNotBlank() && !busy, primary = true, onClick = {
      busy = true
      scope.launch {
        when (val r = AppGraph.library.addLink(url, name)) {
          is AddResult.Added -> {
            AppMessages.say("Added ${r.video.title}")
            onDismiss()
          }
          is AddResult.Rejected -> error = r.reason
        }
        busy = false
      }
    })
  }
}

@Composable
private fun JellyfinSignIn(address: String?, onDismiss: () -> Unit) {
  val type = LocalType.current
  val dims = LocalDims.current
  val scope = rememberCoroutineScope()
  var server by remember { mutableStateOf(address.orEmpty()) }
  var user by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  Text("Connect a Jellyfin server", style = type.heading)
  Text("The address you open Jellyfin with, like 192.168.1.20:8096 or jellyfin.example.com. The sign-in is stored encrypted on ${com.thealgothrim.dwellplay.car.Device.here}.", style = type.meta)
  DwellField(server, "Server address", { server = it; error = null }, keyboardType = KeyboardType.Uri)
  DwellField(user, "User name", { user = it; error = null })
  DwellField(password, "Password", { password = it; error = null }, password = true)
  error?.let { Text(it, style = type.body.copy(color = Palette.Error)) }
  Text(
      "Try Jellyfin's public demo server",
      style = type.body.copy(color = Palette.Text),
      modifier =
          Modifier.clickable(role = Role.Button) {
                server = "https://demo.jellyfin.org/stable"
                user = "demo"
                password = ""
              }
              .padding(vertical = 12.dp),
  )
  ButtonRow {
    ActionButton("Cancel", onDismiss)
    ActionButton(if (busy) "Connecting" else "Connect", enabled = server.isNotBlank() && user.isNotBlank() && !busy, primary = true, onClick = {
      busy = true
      scope.launch {
        try {
          val login = AppGraph.jellyfin.signIn(server, user.trim(), password)
          val s = AppGraph.library.addServer(login)
          AppMessages.say("Connected to ${s.name}")
          onDismiss()
        } catch (e: JellyfinAuthException) {
          error = e.message
        } catch (e: Exception) {
          error = e.message ?: "Couldn't connect."
        }
        busy = false
      }
    })
  }
}

@Composable
private fun Manage(video: Video, onDismiss: () -> Unit, actions: AppActions) {
  val type = LocalType.current
  val dims = LocalDims.current
  val context = LocalContext.current
  val library = AppGraph.library
  val saved by library.saved.collectAsState()
  val isSaved = saved.any { it.id == video.id }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Artwork(video, Modifier.height(dims.touch * 1.4f).aspectRatio(16f / 9f))
    Spacer(Modifier.padding(start = dims.gap / 2))
    Column(Modifier.weight(1f)) {
      Text(video.title, style = type.title, maxLines = 2)
      Text(video.subtitle ?: video.type.label(), style = type.meta, maxLines = 1)
    }
  }
  ButtonRow {
    ActionButton("Play", {
      onDismiss()
      actions.open(video, listOf(video))
    }, icon = Icons.Play, primary = true)
    if (video.type == SourceType.YOUTUBE) {
      val ref = video.ref as? MediaRef.YouTube
      ActionButton("Open in YouTube", { openInYouTube(context, ref?.videoId, ref?.playlistId) }, icon = Icons.Open)
    }
    if (isSaved) {
      ActionButton("Remove", {
        library.removeSaved(video.id)
        AppMessages.say("Removed ${video.title}")
        onDismiss()
      }, icon = Icons.Delete)
    }
    ActionButton("Close", onDismiss)
  }
}

/** Dialog buttons, right-aligned, wrapping to a second line on narrow phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable FlowRowScope.() -> Unit) {
  val dims = LocalDims.current
  FlowRow(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(dims.gap / 2, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(dims.gap / 2),
      content = content,
  )
}

@Composable
fun DwellField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
  val type = LocalType.current
  val dims = LocalDims.current
  OutlinedTextField(
      value = value,
      onValueChange = onChange,
      modifier = Modifier.fillMaxWidth(),
      textStyle = type.body,
      singleLine = true,
      label = { Text(label, style = type.meta) },
      visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
      keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
      shape = RoundedCornerShape(dims.corner),
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedBorderColor = Palette.Accent,
              unfocusedBorderColor = Palette.Hairline,
              focusedLabelColor = Palette.Text,
              cursorColor = Palette.Accent,
          ),
  )
}

private fun clipboardText(context: Context): String? =
    runCatching { context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()?.trim()

/** Pre-fills the link box when the clipboard already holds a link. */
private fun clipboardLink(context: Context): String? = clipboardText(context)?.let { Links.firstUrl(it) }
