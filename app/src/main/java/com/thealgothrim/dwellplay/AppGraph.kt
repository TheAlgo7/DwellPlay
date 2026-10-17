package com.thealgothrim.dwellplay

import android.app.Application
import android.os.Build
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinClient
import com.thealgothrim.dwellplay.core.net.defaultHttpClient
import com.thealgothrim.dwellplay.core.youtube.YouTubeCatalog
import com.thealgothrim.dwellplay.data.LibraryStore
import com.thealgothrim.dwellplay.data.Settings
import com.thealgothrim.dwellplay.playback.PlayerHub
import com.thealgothrim.dwellplay.playback.Resolver
import com.thealgothrim.dwellplay.sources.Library
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.thealgothrim.dwellplay.core.films.FilmCatalog
import com.thealgothrim.dwellplay.sources.FilmArtStore
import okhttp3.OkHttpClient

/** The app's single instances, created on first use. The screens, the player service and the car share them. */
object AppGraph {
  private lateinit var app: Application

  fun init(application: Application) {
    if (!::app.isInitialized) app = application
  }

  val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
  val http: OkHttpClient by lazy { defaultHttpClient() }
  val settings: Settings by lazy { Settings(app) }
  val store: LibraryStore by lazy { LibraryStore(File(app.filesDir, "library.json"), scope) }
  val youtube: YouTubeCatalog by lazy { YouTubeCatalog(http, apiKey = { settings.current.effectiveYouTubeKey }) }
  val jellyfin: JellyfinClient by lazy {
    JellyfinClient(http, deviceId = settings.current.deviceId, deviceName = "${Build.MANUFACTURER} ${Build.MODEL}", appVersion = BuildConfig.VERSION_NAME)
  }
  val filmCatalog: FilmCatalog by lazy { FilmCatalog(http, apiKey = { settings.current.effectiveTmdbKey }, clipHints = { name -> youtube.search(name, max = 5).map { it.title } }) }
  val films: FilmArtStore by lazy { FilmArtStore(app, filmCatalog, enabled = { settings.current.filmArt }, scope) }
  val library: Library by lazy { Library(app, store, jellyfin, youtube, scope, films) }
  val resolver: Resolver by lazy { Resolver(library, jellyfin) }
  val hub: PlayerHub by lazy { PlayerHub(app, library, resolver, settings, jellyfin, scope) }
}
