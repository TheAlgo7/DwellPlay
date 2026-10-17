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

/** The skip lengths offered in Settings. */
val SkipChoices = listOf(5, 10)

data class Prefs(
    /** When the picture has to go (driving, leaving the app), own videos carry on as sound. YouTube always stops. */
    val soundWhenHidden: Boolean = true,
    /** Start the next video in the list when one ends. */
    val autoplayNext: Boolean = true,
    val fit: PictureFit = PictureFit.FIT,
    val captions: Boolean = false,
    /** Seconds for the skip buttons and for double-tapping either side of the picture: 5 or 10. */
    val skipSeconds: Int = 10,
    /** The audio language last picked in the player (dual-audio files), tried first next time. Blank: the file's default. */
    val audioLanguage: String = "",
    /** The subtitle language last picked in the player. Blank: the phone's language. */
    val textLanguage: String = "",
    /** A YouTube Data API key typed in Settings. Falls back to the build's key. */
    val youtubeKey: String = "",
    /** Official film names and wide artwork from TMDB for whole films on the phone. */
    val filmArt: Boolean = true,
    /** A TMDB API key typed in Settings. Falls back to the build's key. */
    val tmdbKey: String = "",
    /** Identifies this install to Jellyfin (so the server can list and revoke it). */
    val deviceId: String = "",
) {
  val effectiveYouTubeKey: String?
    get() = youtubeKey.ifBlank { BuildConfig.YOUTUBE_API_KEY }.ifBlank { null }

  val effectiveTmdbKey: String?
    get() = tmdbKey.ifBlank { BuildConfig.TMDB_API_KEY }.ifBlank { null }
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
        .putInt(K_SKIP, next.skipSeconds)
        .putBoolean(K_FILM_ART, next.filmArt)
        .putString(K_TMDB_KEY, next.tmdbKey)
        .putString(K_AUDIO_LANG, next.audioLanguage)
        .putString(K_TEXT_LANG, next.textLanguage)
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
        skipSeconds = sp.getInt(K_SKIP, 10).takeIf { it in SkipChoices } ?: 10,
        filmArt = sp.getBoolean(K_FILM_ART, true),
        tmdbKey = sp.getString(K_TMDB_KEY, "").orEmpty(),
        audioLanguage = sp.getString(K_AUDIO_LANG, "").orEmpty(),
        textLanguage = sp.getString(K_TEXT_LANG, "").orEmpty(),
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
    const val K_SKIP = "skip_seconds"
    const val K_FILM_ART = "film_art"
    const val K_TMDB_KEY = "tmdb_key"
    const val K_AUDIO_LANG = "audio_language"
    const val K_TEXT_LANG = "text_language"
  }
}
