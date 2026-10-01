package com.thealgothrim.dwellplay.data

import android.content.Context
import com.thealgothrim.dwellplay.BuildConfig
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PictureFit {
  /** Whole picture, black bars where the shapes differ. The default: never crop a concert. */
  FIT,
  /** Fill the frame, cropping the edges. */
  FILL,
}

data class Prefs(
    /** When the picture has to go (driving, leaving the app), own videos carry on as sound. YouTube always stops. */
    val soundWhenHidden: Boolean = true,
    /** Start the next video in the list when one ends. */
    val autoplayNext: Boolean = true,
    val fit: PictureFit = PictureFit.FIT,
    val captions: Boolean = false,
    /** A YouTube Data API key typed in Settings. Falls back to the build's key. */
    val youtubeKey: String = "",
    /** Identifies this install to Jellyfin (so the server can list and revoke it). */
    val deviceId: String = "",
) {
  val effectiveYouTubeKey: String?
    get() = youtubeKey.ifBlank { BuildConfig.YOUTUBE_API_KEY }.ifBlank { null }
}

/** Small preferences in SharedPreferences, exposed as a flow. */
class Settings(context: Context) {
  private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val state = MutableStateFlow(read())
  val prefs: StateFlow<Prefs> = state.asStateFlow()

  val current: Prefs
    get() = state.value

  fun update(change: (Prefs) -> Prefs) {
    val next = change(state.value)
    state.value = next
    sp.edit()
        .putBoolean(K_SOUND, next.soundWhenHidden)
        .putBoolean(K_AUTOPLAY, next.autoplayNext)
        .putString(K_FIT, next.fit.name)
        .putBoolean(K_CAPTIONS, next.captions)
        .putString(K_YT_KEY, next.youtubeKey)
        .apply()
  }

  private fun read(): Prefs {
    var id = sp.getString(K_DEVICE, null)
    if (id == null) {
      id = UUID.randomUUID().toString()
      sp.edit().putString(K_DEVICE, id).apply()
    }
    return Prefs(
        soundWhenHidden = sp.getBoolean(K_SOUND, true),
        autoplayNext = sp.getBoolean(K_AUTOPLAY, true),
        fit = runCatching { PictureFit.valueOf(sp.getString(K_FIT, PictureFit.FIT.name)!!) }.getOrDefault(PictureFit.FIT),
        captions = sp.getBoolean(K_CAPTIONS, false),
        youtubeKey = sp.getString(K_YT_KEY, "").orEmpty(),
        deviceId = id,
    )
  }

  private companion object {
    const val K_SOUND = "sound_when_hidden"
    const val K_AUTOPLAY = "autoplay_next"
    const val K_FIT = "fit"
    const val K_CAPTIONS = "captions"
    const val K_YT_KEY = "youtube_key"
    const val K_DEVICE = "device_id"
  }
}
