package com.thealgothrim.dwellplay

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.thealgothrim.dwellplay.car.AutomotiveRestrictions
import com.thealgothrim.dwellplay.car.CarScreens
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.sources.AddResult
import com.thealgothrim.dwellplay.sources.PhoneVideos
import com.thealgothrim.dwellplay.ui.AppMessages
import com.thealgothrim.dwellplay.ui.AppNav
import com.thealgothrim.dwellplay.ui.DwellApp
import com.thealgothrim.dwellplay.ui.Tab
import com.thealgothrim.dwellplay.ui.theme.DwellTheme
import kotlinx.coroutines.launch

/**
 * The one activity, on whichever screen it's shown: the phone, Android Auto's car screen (as a
 * parked app), or an Android Automotive OS car. It tells the player hub when it's visible and, on
 * Android Automotive OS, when driving restrictions are on; the hub's safety rules do the rest.
 */
class MainActivity : ComponentActivity() {
  private var screen by mutableStateOf(Screen.PHONE)
  private var restricted by mutableStateOf(false)
  private var visible = false
  private var restrictions: AutomotiveRestrictions? = null
  private val nav = AppNav()
  private val windowId: Int
    get() = System.identityHashCode(this)

  override fun onCreate(savedInstanceState: Bundle?) {
    // Always dark: light icons on the dark canvas, whatever the system theme.
    enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    super.onCreate(savedInstanceState)
    AppGraph.init(application)
    screen = CarScreens.detect(this)
    if (screen == Screen.AUTOMOTIVE) {
      val r = AutomotiveRestrictions(this).also { restrictions = it }
      r.start()
      lifecycleScope.launch {
        r.restricted.collect {
          restricted = it
          report()
        }
      }
    }
    // Android Auto's connection state loads a moment after start: look again when it does.
    lifecycleScope.launch { CarScreens.connection.collect { redetect() } }
    AppGraph.hub.connect()
    handleIntent(intent)
    setContent { DwellTheme(screen) { DwellApp(screen, restricted && screen == Screen.AUTOMOTIVE, nav) } }
  }

  override fun onStart() {
    super.onStart()
    visible = true
    report()
    // New downloads show up without a manual refresh.
    AppGraph.library.refreshPhone()
  }

  override fun onStop() {
    visible = false
    report()
    AppGraph.store.flush()
    super.onStop()
  }

  override fun onDestroy() {
    AppGraph.hub.forgetWindow(windowId)
    restrictions?.stop()
    super.onDestroy()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    redetect()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  private fun redetect() {
    val s = CarScreens.detect(this)
    if (s != screen) {
      screen = s
      report()
    }
  }

  private fun report() = AppGraph.hub.reportWindow(windowId, screen, visible, restricted)

  private fun handleIntent(intent: Intent?) {
    when (intent?.action) {
      // Shared from the YouTube app or a browser.
      Intent.ACTION_SEND -> {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        lifecycleScope.launch {
          when (val r = AppGraph.library.addLink(text, subject)) {
            is AddResult.Added -> {
              AppMessages.say("Added ${r.video.title}")
              nav.playerOpen = false
              nav.tab = Tab.HOME
            }
            is AddResult.Rejected -> AppMessages.say(r.reason)
          }
        }
      }
      // "Open with DwellPlay" on a video file.
      Intent.ACTION_VIEW -> {
        val uri = intent.data ?: return
        val video =
            Video(
                id = "opened:$uri",
                sourceId = PhoneVideos.SOURCE_ID,
                type = SourceType.PHONE,
                title = displayName(uri) ?: "Video",
                ref = MediaRef.Content(uri.toString(), intent.type),
            )
        AppGraph.hub.play(video)
        nav.playerOpen = true
      }
      else ->
          // Android Auto reopens a parked app that has something playing with this action
          // (seen on the head unit): go straight to the player, which waits on "Resume video".
          if (intent?.action?.endsWith("MEDIA_SHOW_PLAYBACK_VIEW") == true && AppGraph.hub.now.value != null) nav.playerOpen = true
    }
  }

  private fun displayName(uri: Uri): String? =
      runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
          }
          .getOrNull()
          ?.let { PhoneVideos.niceTitle(null, it) }
}
