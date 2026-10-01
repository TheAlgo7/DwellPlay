package com.thealgothrim.dwellplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.core.search.LocalSearch
import com.thealgothrim.dwellplay.sources.SearchResults
import com.thealgothrim.dwellplay.ui.theme.LocalDims
import com.thealgothrim.dwellplay.ui.theme.LocalHostCorners
import com.thealgothrim.dwellplay.ui.theme.LocalType
import com.thealgothrim.dwellplay.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * One search box for everything. What's on the device answers as you type; YouTube and servers
 * are asked when you press search (each YouTube search costs API quota).
 */
@Composable
fun SearchScreen(actions: AppActions) {
  val library = AppGraph.library
  val dims = LocalDims.current
  val type = LocalType.current
  val scope = rememberCoroutineScope()
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }
  var query by rememberSaveable { mutableStateOf("") }
  var results by remember { mutableStateOf<SearchResults?>(null) }
  var searching by remember { mutableStateOf(false) }
  val phone by library.phone.collectAsState()
  val saved by library.saved.collectAsState()
  val data by library.data.collectAsState()
  val progressOf = { v: Video -> ResumeRules.fraction(data.progress[v.id]) }

  val local = remember(query, phone, saved) { if (query.isBlank()) emptyList() else LocalSearch.search(phone + saved + data.recent, query) }

  fun runSearch() {
    val q = query.trim()
    if (q.isEmpty()) return
    focus.clearFocus()
    keyboard?.hide()
    searching = true
    scope.launch {
      results = library.search(q)
      searching = false
    }
  }

  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

  Column(Modifier.fillMaxSize()) {
    OutlinedTextField(
        value = query,
        onValueChange = {
          query = it
          if (results?.query != it.trim()) results = null
        },
        // Stops short of Android Auto's Exit button, which would cover the clear button.
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = dims.gutter, end = dims.gutter + LocalHostCorners.current.end, top = dims.gap / 2, bottom = dims.gap / 2)
                .focusRequester(focusRequester),
        textStyle = type.title,
        singleLine = true,
        placeholder = { Text("Search videos, YouTube and your server", style = type.body.copy(color = Palette.Text3)) },
        leadingIcon = { Icon(Icons.Search, null, modifier = Modifier.size(dims.icon)) },
        trailingIcon = {
          if (query.isNotEmpty()) IconAction(Icons.Close, "Clear", onClick = { query = ""; results = null })
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(dims.corner * 2),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.Hairline,
                focusedContainerColor = Palette.Surface,
                unfocusedContainerColor = Palette.Surface,
                cursorColor = Palette.Accent,
            ),
    )
    val r = results
    if (query.isBlank()) {
      MessagePanel(Icons.Search, "Find anything", "Titles on ${com.thealgothrim.dwellplay.car.Device.here} as you type. Press search to ask YouTube and your Jellyfin servers too.", Modifier.fillMaxSize())
      return@Column
    }
    LazyColumn(contentPadding = PaddingValues(bottom = dims.section * 2), verticalArrangement = Arrangement.spacedBy(dims.section)) {
      item {
        Row(Modifier.padding(horizontal = dims.gutter), verticalAlignment = Alignment.CenterVertically) {
          ActionButton(if (searching) "Searching" else "Search YouTube and servers", ::runSearch, icon = Icons.Search, primary = r == null, enabled = !searching)
          if (searching) {
            Spacer(Modifier.width(12.dp))
            Spinner()
          }
        }
      }
      if (local.isNotEmpty()) item { CardRow("On your devices", local, progressOf, actions.open) }
      if (r != null) {
        if (r.servers.isNotEmpty()) item { CardRow("Jellyfin", r.servers, progressOf, actions.open) }
        if (r.youtube.isNotEmpty()) item { CardRow("YouTube", r.youtube, progressOf, actions.open) }
        r.youtubeNote?.let { note -> item { InlineNotice(note, "Settings") { actions.goTo(Tab.SETTINGS) } } }
        if (local.isEmpty() && r.servers.isEmpty() && r.youtube.isEmpty()) {
          item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Nothing matched \"${r.query}\".", style = type.body.copy(color = Palette.Text2)) } }
        }
      }
    }
  }
}
