package com.thealgothrim.dwellplay.sources

import android.content.Context
import android.util.Log
import com.thealgothrim.dwellplay.core.films.FilmArt
import com.thealgothrim.dwellplay.core.films.FilmCatalog
import com.thealgothrim.dwellplay.core.films.FilmNames
import com.thealgothrim.dwellplay.core.films.FilmQuery
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.net.DwellJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Official names and wide artwork for whole films among the phone's and folders' videos, looked up
 * once on TMDB and kept on the phone. Clips and episodes keep their own names. A film TMDB doesn't
 * know is asked about again after a week; a network failure, on the next launch.
 */
class FilmArtStore(
    context: Context,
    private val catalog: FilmCatalog,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
) {
  private val sp = context.getSharedPreferences("film_art", Context.MODE_PRIVATE)
  private val _art = MutableStateFlow(load())
  val art: StateFlow<Map<String, FilmArt>> = _art.asStateFlow()
  private val asked = HashSet<String>()

  fun request(videos: List<Video>) {
    if (!enabled() || !catalog.enabled) return
    val today = System.currentTimeMillis() / DAY
    val todo =
        videos.asSequence()
            .filter { (it.type == SourceType.PHONE || it.type == SourceType.FOLDER) && (MANUAL + it.id) !in _art.value }
            .mapNotNull { FilmNames.parse(it.title) }
            .distinctBy { it.key }
            .filter { q -> q.key !in _art.value && q.key !in asked && sp.getLong(MISS + q.key, -1L).let { it < 0 || today - it >= 7 } }
            .toList()
    if (todo.isEmpty()) return
    asked += todo.map { it.key }
    scope.launch {
      for (q in todo) {
        runCatching { catalog.lookup(q) }
            .onSuccess { found ->
              if (found == null) {
                sp.edit().putLong(MISS + q.key, today).apply()
              } else {
                sp.edit().putString(HIT + q.key, DwellJson.encodeToString(FilmArt.serializer(), found)).remove(MISS + q.key).apply()
                _art.update { it + (q.key to found) }
              }
            }
            .onFailure { Log.w(TAG, "Film lookup failed for ${q.title}", it) }
      }
    }
  }

  /**
   * The person's own choice for one video: a film ([clip] when the video is only a part of it), or
   * null for "no film, use the video's own picture". It beats any automatic match.
   */
  fun choose(video: Video, art: FilmArt?) {
    val chosen = art ?: FilmArt(title = "")
    sp.edit().putString(HIT + MANUAL + video.id, DwellJson.encodeToString(FilmArt.serializer(), chosen)).apply()
    _art.update { it + ((MANUAL + video.id) to chosen) }
  }

  /** The video with its film's name, year and artwork, when known. Safe to apply twice. */
  fun apply(v: Video): Video {
    if (v.type != SourceType.PHONE && v.type != SourceType.FOLDER) return v
    val q = FilmNames.parse(v.title) ?: FilmQuery(v.title, null)
    val manual = _art.value[MANUAL + v.id]
    // Chosen by hand as "no film": the tidied name and the video's own picture.
    if (manual != null && manual.title.isEmpty()) return if (q.title == v.title.trim()) v else v.copy(title = q.title)
    val a = (manual ?: _art.value[q.key])?.takeIf { enabled() }
    if (a == null) {
      // Not a known film (a clip, a home video): drop release tags from the name, keep the year as a detail.
      if (q.title == v.title.trim()) return v
      return v.copy(title = q.title, subtitle = listOfNotNull(q.year?.toString(), v.subtitle).joinToString(" · ").ifBlank { null })
    }
    val art = a.backdropUrl ?: a.posterUrl
    if (art != null && v.artworkUrl == art) return v
    // A clip keeps its own (tidied) name and says which film it is from.
    if (a.clip) return v.copy(title = q.title, subtitle = listOfNotNull(a.title, v.subtitle).joinToString(" · "), artworkUrl = art ?: v.artworkUrl)
    return v.copy(
        title = a.title,
        subtitle = listOfNotNull(a.year?.toString(), v.subtitle).joinToString(" · ").ifBlank { null },
        artworkUrl = art ?: v.artworkUrl,
    )
  }

  fun clear() {
    sp.edit().clear().apply()
    asked.clear()
    _art.value = emptyMap()
  }

  private fun load(): Map<String, FilmArt> =
      sp.all.mapNotNull { (k, v) ->
            if (!k.startsWith(HIT) || v !is String) null
            else runCatching { k.removePrefix(HIT) to DwellJson.decodeFromString(FilmArt.serializer(), v) }.getOrNull()
          }
          .toMap()

  private companion object {
    const val TAG = "FilmArt"
    // Version 3 adds clips matched to their film; older entries are looked up again.
    const val HIT = "hit3:"
    const val MISS = "miss4:"
    const val MANUAL = "id:"
    const val DAY = 86_400_000L
  }
}
