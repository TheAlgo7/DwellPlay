package com.thealgothrim.dwellplay.sources

import android.content.Context
import android.net.Uri
import android.util.Log
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinAuthException
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinClient
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinConnection
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinLogin
import com.thealgothrim.dwellplay.core.links.LinkKind
import com.thealgothrim.dwellplay.core.links.Links
import com.thealgothrim.dwellplay.core.model.JellyfinServer
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.Source
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.Progress
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.core.search.LocalSearch
import com.thealgothrim.dwellplay.core.youtube.YOUTUBE_SOURCE_ID
import com.thealgothrim.dwellplay.core.youtube.YouTubeCatalog
import com.thealgothrim.dwellplay.data.LibraryData
import com.thealgothrim.dwellplay.data.LibraryStore
import com.thealgothrim.dwellplay.data.SecretBox
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val STREAMS_SOURCE_ID = "streams"

/** A Jellyfin server's rows on the home screen. */
data class ServerShelf(
    val resume: List<Video> = emptyList(),
    val latest: List<Video> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The saved sign-in no longer works. */
    val signInAgain: Boolean = false,
)

data class SearchResults(
    val query: String,
    val local: List<Video> = emptyList(),
    val youtube: List<Video> = emptyList(),
    val servers: List<Video> = emptyList(),
    val youtubeNote: String? = null,
)

sealed interface AddResult {
  data class Added(val video: Video) : AddResult
  data class Rejected(val reason: String) : AddResult
}

/** Every source in one place: what the screens list, search and play from. */
class Library(
    private val context: Context,
    private val store: LibraryStore,
    private val jellyfin: JellyfinClient,
    private val youtube: YouTubeCatalog,
    private val scope: CoroutineScope,
    private val films: FilmArtStore,
) {
  val phoneAccess = MutableStateFlow(PhoneVideos.access(context))

  private val _phone = MutableStateFlow<List<Video>>(emptyList())
  val phone: StateFlow<List<Video>> = _phone.asStateFlow()

  private val _folders = MutableStateFlow<Map<String, List<Video>>>(emptyMap())
  val folders: StateFlow<Map<String, List<Video>>> = _folders.asStateFlow()

  // What the scans found, before film names and artwork are applied.
  private var phoneRaw = emptyList<Video>()
  private val foldersRaw = HashMap<String, List<Video>>()

  init {
    // A film's artwork arriving later redraws the lists it is in.
    scope.launch {
      films.art.collect {
        _phone.value = phoneRaw.map(films::apply)
        _folders.value = foldersRaw.mapValues { (_, l) -> l.map(films::apply) }
      }
    }
  }

  private val _shelves = MutableStateFlow<Map<String, ServerShelf>>(emptyMap())
  val shelves: StateFlow<Map<String, ServerShelf>> = _shelves.asStateFlow()

  val data: StateFlow<LibraryData> = store.data
  val sources: StateFlow<List<Source>> = store.data.map { it.sources }.stateIn(scope, SharingStarted.Eagerly, store.current.sources)
  val saved: StateFlow<List<Video>> = store.data.map { it.saved }.stateIn(scope, SharingStarted.Eagerly, store.current.saved)

  /** Part-watched titles, newest first, including what a server says was started elsewhere. */
  /**
   * What was played lately, minus phone videos outside the DwellPlay folder: something played once
   * from another folder must not resurface on the car screen through history or search.
   */
  val history: StateFlow<List<Video>> =
      combine(store.data, _phone, films.art) { d, phone, _ -> d.recent.filter { v -> v.type != SourceType.PHONE || phone.any { it.id == v.id } }.map(films::apply) }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  val continueWatching: StateFlow<List<Video>> =
      combine(store.data, _shelves, history) { d, shelves, recent ->
            val mine = recent.filter { ResumeRules.fraction(d.progress[it.id]) != null }
            val fromServers = shelves.values.flatMap { it.resume }.filter { v -> mine.none { it.id == v.id } }
            (mine + fromServers).take(16)
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  // ---- Refreshing ----

  fun refreshAll() {
    refreshPhone()
    sources.value.forEach { refresh(it) }
    repairTitles()
  }

  /** YouTube links added while offline got a stand-in title; fetch the real one now that the network may be back. */
  private fun repairTitles() {
    val stale = saved.value.filter { it.type == SourceType.YOUTUBE && YouTubeCatalog.isPlaceholder(it.title) }
    if (stale.isEmpty()) return
    scope.launch {
      for (v in stale) {
        val ref = v.ref as? MediaRef.YouTube ?: continue
        val fresh = runCatching { youtube.describe(ref.videoId, ref.playlistId, ref.startSeconds, v.addedAtMs) }.getOrNull() ?: continue
        if (YouTubeCatalog.isPlaceholder(fresh.title)) continue
        store.update { d -> d.copy(saved = d.saved.map { if (it.id == v.id) it.copy(title = fresh.title, subtitle = fresh.subtitle, artworkUrl = fresh.artworkUrl ?: it.artworkUrl) else it }) }
      }
    }
  }

  private var phoneScan: Job? = null

  /** A newer request replaces a scan in flight, so a slow older scan can never overwrite its result. */
  fun refreshPhone() {
    phoneScan?.cancel()
    phoneScan =
        scope.launch {
          phoneAccess.value = PhoneVideos.access(context)
          phoneRaw =
              try {
                PhoneVideos.scan(context).also { Log.i(TAG, "Phone videos: ${it.size} (access ${phoneAccess.value})") }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.w(TAG, "Phone video scan failed", e)
                emptyList()
              }
          _phone.value = phoneRaw.map(films::apply)
          films.request(phoneRaw)
        }
  }

  fun refresh(source: Source) {
    when (source.type) {
      SourceType.FOLDER ->
          scope.launch {
            val found = runCatching { FolderVideos.scan(context, source) }.getOrElse { emptyList() }
            foldersRaw[source.id] = found
            _folders.update { it + (source.id to found.map(films::apply)) }
            films.request(found)
          }
      SourceType.JELLYFIN -> refreshServer(source)
      else -> Unit
    }
  }

  private fun refreshServer(source: Source) {
    scope.launch {
      val c = connection(source.id)
      if (c == null) {
        _shelves.update { it + (source.id to ServerShelf(error = "Sign in to ${source.name} again.", signInAgain = true)) }
        return@launch
      }
      _shelves.update { it + (source.id to (it[source.id] ?: ServerShelf()).copy(loading = true, error = null)) }
      try {
        val (resume, latest) =
            coroutineScope {
              val r = async { jellyfin.resume(c) }
              val l = async { jellyfin.latest(c) }
              r.await() to l.await()
            }
        // The server remembers positions from its other apps; use them where this app has none.
        store.update { d ->
          val seeded = d.progress.toMutableMap()
          for (item in resume) {
            val id = "jf:${source.id}:${item.Id}"
            val pos = jellyfin.serverPositionMs(item) ?: continue
            if (seeded[id] == null) seeded[id] = Progress(pos, item.RunTimeTicks?.let { it / 10_000 }, System.currentTimeMillis())
          }
          d.copy(progress = seeded)
        }
        _shelves.update {
          it + (source.id to ServerShelf(resume.map { i -> jellyfin.toVideo(c, i) }, latest.map { i -> jellyfin.toVideo(c, i) }))
        }
      } catch (e: JellyfinAuthException) {
        _shelves.update { it + (source.id to ServerShelf(error = "Sign in to ${source.name} again.", signInAgain = true)) }
      } catch (e: Exception) {
        Log.w(TAG, "Server ${source.name} unreachable", e)
        _shelves.update { it + (source.id to (it[source.id] ?: ServerShelf()).copy(loading = false, error = "Can't reach ${source.name}.")) }
      }
    }
  }

  /** The signed-in connection for a Jellyfin source, or null if its sign-in can't be opened. */
  fun connection(sourceId: String): JellyfinConnection? {
    val s = sources.value.firstOrNull { it.id == sourceId } ?: return null
    val server = s.server ?: return null
    val token = SecretBox.open(server.sealedToken) ?: return null
    return JellyfinConnection(s.id, server.baseUrl, token, server.userId, server.serverId)
  }

  /** A Jellyfin folder's contents; the server's libraries when [parentId] is null. */
  suspend fun browseServer(sourceId: String, parentId: String?): List<Video> {
    val c = connection(sourceId) ?: throw JellyfinAuthException("Sign in to this server again.")
    val items = if (parentId == null) jellyfin.views(c) else jellyfin.children(c, parentId)
    return items.map { jellyfin.toVideo(c, it) }
  }

  // ---- Adding and removing ----

  /** A link shared from another app or pasted: YouTube becomes an embed entry, anything else a stream. */
  suspend fun addLink(text: String, name: String? = null): AddResult =
      when (val kind = Links.classify(text)) {
        is LinkKind.YouTube -> {
          val v = youtube.describe(kind.link.videoId, kind.link.playlistId, kind.link.startSeconds)
          save(v)
          AddResult.Added(v)
        }
        is LinkKind.Stream -> {
          val v =
              Video(
                  id = "stream:${UUID.randomUUID()}",
                  sourceId = STREAMS_SOURCE_ID,
                  type = SourceType.STREAM,
                  title = name?.takeIf { it.isNotBlank() } ?: Links.defaultName(kind.url),
                  subtitle = Uri.parse(kind.url).host,
                  ref = MediaRef.Stream(kind.url, kind.kind),
                  addedAtMs = System.currentTimeMillis(),
              )
          save(v)
          AddResult.Added(v)
        }
        is LinkKind.Unsupported -> AddResult.Rejected(kind.reason)
      }

  private fun save(v: Video) = store.update { d -> d.copy(saved = listOf(v) + d.saved.filter { it.id != v.id }) }

  fun removeSaved(videoId: String) = store.update { d -> d.copy(saved = d.saved.filter { it.id != videoId }, recent = d.recent.filter { it.id != videoId }) }

  fun rename(videoId: String, title: String) =
      store.update { d -> d.copy(saved = d.saved.map { if (it.id == videoId) it.copy(title = title) else it }) }

  fun addFolder(tree: Uri): Source {
    FolderVideos.keepAccess(context, tree)
    val existing = sources.value.firstOrNull { it.folderUri == tree.toString() }
    val source =
        existing
            ?: Source(
                id = "folder-${UUID.randomUUID().toString().take(8)}",
                type = SourceType.FOLDER,
                name = FolderVideos.displayName(context, tree),
                folderUri = tree.toString(),
                addedAtMs = System.currentTimeMillis(),
            )
    if (existing == null) store.update { d -> d.copy(sources = d.sources + source) }
    refresh(source)
    return source
  }

  /** Adds or refreshes a Jellyfin sign-in. The token is sealed before it touches storage. */
  fun addServer(login: JellyfinLogin): Source {
    val server =
        JellyfinServer(login.baseUrl, login.serverId, login.serverName, login.userId, login.userName, SecretBox.seal(login.token))
    val existing = sources.value.firstOrNull { it.server?.serverId == login.serverId && it.server?.userId == login.userId }
    val source =
        existing?.copy(server = server, name = login.serverName)
            ?: Source(
                id = "jf-${UUID.randomUUID().toString().take(8)}",
                type = SourceType.JELLYFIN,
                name = login.serverName,
                server = server,
                addedAtMs = System.currentTimeMillis(),
            )
    store.update { d -> d.copy(sources = d.sources.filter { it.id != source.id } + source) }
    refresh(source)
    return source
  }

  fun removeSource(sourceId: String) {
    val s = sources.value.firstOrNull { it.id == sourceId } ?: return
    s.folderUri?.let { FolderVideos.releaseAccess(context, Uri.parse(it)) }
    store.update { d -> d.copy(sources = d.sources.filter { it.id != sourceId }, recent = d.recent.filter { it.sourceId != sourceId }) }
    foldersRaw.remove(sourceId)
    _folders.update { it - sourceId }
    _shelves.update { it - sourceId }
  }

  // ---- Progress ----

  fun progressOf(videoId: String): Progress? = store.current.progress[videoId]

  fun saveProgress(videoId: String, positionMs: Long, durationMs: Long?) {
    if (positionMs < 0) return
    store.update { d -> d.copy(progress = d.progress + (videoId to Progress(positionMs, durationMs?.takeIf { it > 0 }, System.currentTimeMillis()))) }
  }

  fun markPlayed(video: Video) =
      store.update { d -> d.copy(recent = (listOf(video) + d.recent.filter { it.id != video.id }).take(40)) }

  fun clearHistory() = store.update { d -> d.copy(recent = emptyList(), progress = emptyMap()) }

  // ---- Lookup and search ----

  fun videosOf(sourceId: String): List<Video> =
      when (sourceId) {
        PhoneVideos.SOURCE_ID -> _phone.value
        YOUTUBE_SOURCE_ID -> saved.value.filter { it.type == SourceType.YOUTUBE }
        STREAMS_SOURCE_ID -> saved.value.filter { it.type == SourceType.STREAM }
        else -> _folders.value[sourceId] ?: _shelves.value[sourceId]?.let { it.resume + it.latest }.orEmpty()
      }

  fun find(videoId: String): Video? {
    val d = store.current
    return _phone.value.firstOrNull { it.id == videoId }
        ?: d.saved.firstOrNull { it.id == videoId }
        ?: d.recent.firstOrNull { it.id == videoId }
        ?: _folders.value.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == videoId } }
        ?: _shelves.value.values.firstNotNullOfOrNull { s -> (s.resume + s.latest).firstOrNull { it.id == videoId } }
  }

  private fun everythingLocal(): List<Video> = (_phone.value + _folders.value.values.flatten() + saved.value + history.value).distinctBy { it.id }

  suspend fun search(query: String): SearchResults = coroutineScope {
    val q = query.trim()
    if (q.isEmpty()) return@coroutineScope SearchResults(q)
    val local = LocalSearch.search(everythingLocal(), q)
    val servers = async {
      sources.value.filter { it.type == SourceType.JELLYFIN }.map { s ->
        async {
          val c = connection(s.id) ?: return@async emptyList()
          runCatching { jellyfin.search(c, q).map { jellyfin.toVideo(c, it) } }.getOrElse { emptyList() }
        }
      }.awaitAll().flatten()
    }
    val yt = async {
      if (!youtube.canSearch) return@async emptyList<Video>() to "Add a YouTube API key in Settings to search YouTube. Shared links work without one."
      runCatching { youtube.search(q) to null }.getOrElse { emptyList<Video>() to (it.message ?: "YouTube search failed.") }
    }
    val (ytResults, note) = yt.await()
    SearchResults(q, local, ytResults, servers.await(), note)
  }

  private companion object {
    const val TAG = "Library"
  }
}
