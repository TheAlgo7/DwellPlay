package com.thealgothrim.dwellplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.sources.AddResult
import kotlinx.coroutines.launch

/**
 * Debug builds only: drive the app from adb so tests never need blind taps. Every command logs
 * under the tag DwellDebug.
 *
 *   R="-n com.thealgothrim.dwellplay.debug/com.thealgothrim.dwellplay.DebugReceiver"
 *   adb shell am broadcast $R -a add --es text "https://youtu.be/..."        (a YouTube or stream link)
 *   adb shell am broadcast $R -a play --es id yt:VIDEOID                     (or --es first phone|youtube|stream|jellyfin, or --es title "part of a title")
 *   adb shell am broadcast $R -a jellyfin --es url https://demo.jellyfin.org/stable --es user demo --es password ""
 *   adb shell am broadcast $R -a drive --ez on true                          (pretend the car moves / parks again)
 *   adb shell am broadcast $R -a car --ez on true                            (a second display counts as Android Auto's)
 *   adb shell am broadcast $R -a only --es files "a.mp4|b.mp4"                (phone videos: only these files; "" for all)
 *   adb shell am broadcast $R -a resume | toggle | close | convert | state
 */
class DebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val done = goAsync()
    val hub = AppGraph.hub
    val library = AppGraph.library
    AppGraph.scope.launch {
      try {
        when (intent.action) {
          "add" -> {
            val r = library.addLink(intent.getStringExtra("text").orEmpty(), intent.getStringExtra("name"))
            Log.i(TAG, "add: " + if (r is AddResult.Added) "added ${r.video.id} '${r.video.title}'" else "rejected ${(r as AddResult.Rejected).reason}")
          }
          "play" -> {
            val id = intent.getStringExtra("id")
            val first = intent.getStringExtra("first")
            val title = intent.getStringExtra("title")
            val all = library.phone.value + library.saved.value + library.shelves.value.values.flatMap { it.latest }
            val video =
                when {
                  id != null -> library.find(id)
                  title != null -> all.firstOrNull { it.title.contains(title, ignoreCase = true) && !it.isFolder }
                  first != null -> all.firstOrNull { it.type == SourceType.valueOf(first.uppercase()) && !it.isFolder }
                  else -> null
                }
            if (video == null) Log.w(TAG, "play: nothing matched id=$id first=$first")
            else {
              hub.play(video, all.filter { it.type == video.type && !it.isFolder })
              Log.i(TAG, "play: ${video.id} '${video.title}'")
            }
          }
          "jellyfin" -> {
            val login = AppGraph.jellyfin.signIn(intent.getStringExtra("url").orEmpty(), intent.getStringExtra("user").orEmpty(), intent.getStringExtra("password").orEmpty())
            val s = library.addServer(login)
            Log.i(TAG, "jellyfin: signed in to ${s.name} as ${login.userName}")
          }
          "drive" -> {
            hub.debugDriving(intent.getBooleanExtra("on", true))
            Log.i(TAG, "drive: ${intent.getBooleanExtra("on", true)} -> ${hub.safety.value}")
          }
          "car" -> {
            com.thealgothrim.dwellplay.car.CarScreens.pretendProjecting = intent.getBooleanExtra("on", true)
            Log.i(TAG, "car: second displays count as Android Auto = ${com.thealgothrim.dwellplay.car.CarScreens.pretendProjecting}")
          }
          "only" -> {
            val files = intent.getStringExtra("files").orEmpty()
            context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putString("only_files", files.ifBlank { null }).commit()
            library.refreshPhone()
            Log.i(TAG, "only: ${files.split('|').count { it.isNotBlank() }} test files")
          }
          "convert" -> hub.askServerToConvert()
          "resume" -> hub.resumeVideo()
          "toggle" -> hub.togglePlay()
          "mute" -> hub.setMuted(intent.getBooleanExtra("on", true))
          "close" -> hub.close()
          "state" -> Unit
          "library" -> library.phone.value.forEach { Log.i(TAG, "library: ${it.title} | ${it.subtitle} | ${it.artworkUrl}") }
          else -> Log.w(TAG, "unknown action ${intent.action}")
        }
        Log.i(TAG, "state: now=${hub.now.value?.video?.id} safety=${hub.safety.value} ui=${hub.ui.value} problem=${hub.problem.value} youtube=${hub.youtube.value}")
      } catch (e: Exception) {
        Log.w(TAG, "${intent.action} failed", e)
      } finally {
        done.finish()
      }
    }
  }

  private companion object {
    const val TAG = "DwellDebug"
  }
}
