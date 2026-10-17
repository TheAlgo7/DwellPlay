package com.thealgothrim.dwellplay.core.films

import com.thealgothrim.dwellplay.core.net.DwellJson
import com.thealgothrim.dwellplay.core.net.fetch
import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A film name read from a file name: "Spider-Man Brand New Day (2026) 1080p Dual Audio.mkv". */
data class FilmQuery(val title: String, val year: Int?) {
  /** One key per film, for caching lookups. */
  val key: String
    get() = "${FilmNames.normalize(title)}|${year ?: ""}"
}

/** A film offered when choosing by hand. */
data class FilmChoice(val id: Long, val title: String, val year: Int?, val posterUrl: String?)

/** The official name and artwork of a film, from TMDB. */
@Serializable
data class FilmArt(
    val title: String,
    val year: Int? = null,
    /** Wide still (16:9), the film's backdrop. */
    val backdropUrl: String? = null,
    /** Tall poster (2:3). */
    val posterUrl: String? = null,
    /** True when the video is a clip from this film: the clip keeps its own name. */
    val clip: Boolean = false,
)

object FilmNames {
  private val extension = Regex("""\.[A-Za-z0-9]{2,4}$""")
  private val year = Regex("""(?:^|[\s(\[])((?:19|20)\d{2})(?:$|[\s)\]])""")
  /** Release tags: everything from the first of these on is not part of the title. */
  private val tag =
      Regex(
          """(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr|hdr10|dv|imax|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|web|hdrip|dvdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|avc|aac|ac3|eac3|ddp?\d?(\.\d)?|dts|atmos|10bit|dual[\s-]?audio|multi|hindi|english|esub|esubs|proper|repack|extended|remastered|uncut)\b""")
  private val episode = Regex("""(?i)\bS\d{1,2}\s?E\d{1,3}\b""")

  /**
   * The film in a file name, or null when it doesn't look like a whole film: TV episodes, and names
   * that are only release tags. The title stops at the year or the first release tag.
   */
  fun parse(fileName: String): FilmQuery? {
    var name = fileName.replace(extension, "").replace('_', ' ')
    // Dots between words ("The.Batman.2022.1080p") but not inside "H.264" or "5.1".
    if (!name.contains(' ')) name = name.replace('.', ' ')
    if (episode.containsMatchIn(name)) return null
    val y = year.find(name)
    val tagAt = tag.find(name)?.range?.first
    val cut = listOfNotNull(y?.range?.first, tagAt).minOrNull() ?: name.length
    val title = name.substring(0, cut).replace(Regex("""[\[(]\s*$"""), "").trim().trimEnd('-', ' ').trim()
    if (title.length < 2) return null
    return FilmQuery(title, y?.groupValues?.get(1)?.toInt())
  }

  /** Lowercase letters and digits only, so "Spider-Man: Brand New Day" equals "Spider-Man Brand New Day". */
  fun normalize(s: String): String =
      Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

  /** The words of a title, lowercase: "Spider-Man: No Way Home" gives spider, man, no, way, home. */
  fun words(s: String): List<String> =
      Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("""\p{M}+"""), "").lowercase().replace("&", " and ").split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }

  /**
   * A clip from this film: every word of the film's title is in the clip's name, and the years (when
   * both known) are at most a year apart. A one-word title needs the year too, or "Thor" would claim
   * every clip with Thor in it.
   */
  fun clipOf(q: FilmQuery, title: String, year: Int?): Boolean {
    val film = words(title)
    if (film.isEmpty() || !words(q.title).containsAll(film)) return false
    if (q.year != null && year != null && kotlin.math.abs(q.year - year) > 1) return false
    return film.size >= 2 || (q.year != null && year != null)
  }

  /** The same film: titles equal once normalized, and the years (when both known) at most a year apart. */
  fun matches(q: FilmQuery, title: String, year: Int?): Boolean =
      normalize(q.title) == normalize(title) && (q.year == null || year == null || kotlin.math.abs(q.year - year) <= 1)
}

/**
 * Official film names and artwork from TMDB (themoviedb.org), for videos on the phone that are
 * whole films. Only the title and year read from the file name are sent. An exact title match is
 * required, so a clip named after a film ("No Way Home - Final Battle") keeps its own name.
 */
class FilmCatalog(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    /**
     * Titles of videos elsewhere that share a clip's name, for clips whose file name never says
     * which film they're from ("Thor Arrives in Wakanda" is from Avengers: Infinity War). Official
     * clip uploads name their film, so YouTube's results for the clip's name usually do.
     */
    private val clipHints: suspend (String) -> List<String> = { emptyList() },
    private val apiBase: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl(),
    private val imageBase: String = "https://image.tmdb.org/t/p/",
) {
  val enabled: Boolean
    get() = !apiKey().isNullOrBlank()

  /** The film, or null when TMDB has no exact match. Throws on network errors, so callers can retry later. */
  suspend fun lookup(q: FilmQuery): FilmArt? {
    val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
    val url =
        apiBase.newBuilder().addPathSegments("search/movie")
            .addQueryParameter("api_key", key)
            .addQueryParameter("query", q.title)
            .addQueryParameter("include_adult", "false")
            .apply { q.year?.let { addQueryParameter("year", it.toString()) } }
            .build()
    var results = DwellJson.decodeFromString(Search.serializer(), http.fetch(Request.Builder().url(url).build())).results
    // A file's year is often the release in India, a year after the first release elsewhere.
    if (results.none { matchesResult(q, it) } && q.year != null) {
      val any = url.newBuilder().removeAllQueryParameters("year").build()
      results = DwellJson.decodeFromString(Search.serializer(), http.fetch(Request.Builder().url(any).build())).results
    }
    val hit = results.firstOrNull { matchesResult(q, it) } ?: return lookupClip(q, key)
    // A whole film gets a backdrop with its name on it, so the tile says which film it is.
    return art(hit, key, titled = true, clip = false)
  }

  /**
   * A clip named after its film ("Spider-Man No Way Home - Final Battle"): the film whose every
   * title word is in the clip's name, the longest such title winning. Its art is a textless still.
   */
  private suspend fun lookupClip(q: FilmQuery, key: String): FilmArt? {
    clipFilm(q, key)?.let { return art(it, key, titled = false, clip = true) }
    // The name doesn't say which film: ask what the clip is called where it was published.
    val hints = runCatching { clipHints(listOfNotNull(q.title, q.year?.toString()).joinToString(" ")) }.getOrDefault(emptyList())
    val own = FilmNames.words(q.title).toSet()
    for (hint in hints.take(5)) {
      // Only results about the same clip: at least half its words, and three or more.
      val shared = FilmNames.words(hint).toSet().intersect(own).size
      if (shared < 3 || shared * 2 < own.size) continue
      val h = FilmNames.parse(hint) ?: continue
      val film = clipFilm(FilmQuery(h.title, q.year ?: h.year), key) ?: continue
      return art(film, key, titled = false, clip = true)
    }
    return null
  }

  /** Films for a typed search, most popular first. */
  suspend fun search(text: String): List<FilmChoice> {
    val key = apiKey()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return searchMovies(text, key).sortedByDescending { it.popularity }.take(12).map { FilmChoice(it.id, it.title, it.year, it.posterPath?.let { p -> "${imageBase}w185$p" }) }
  }

  /** Artwork for a film chosen by hand: titled for a whole film, a plain still for a clip. */
  suspend fun artFor(choice: FilmChoice, clip: Boolean): FilmArt? {
    val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
    val url = apiBase.newBuilder().addPathSegments("movie/${choice.id}").addQueryParameter("api_key", key).build()
    val r = DwellJson.decodeFromString(Result.serializer(), http.fetch(Request.Builder().url(url).build()))
    return art(r, key, titled = !clip, clip = clip)
  }

  private suspend fun searchMovies(text: String, key: String): List<Result> {
    val url = apiBase.newBuilder().addPathSegments("search/movie").addQueryParameter("api_key", key).addQueryParameter("query", text).addQueryParameter("include_adult", "false").build()
    return DwellJson.decodeFromString(Search.serializer(), http.fetch(Request.Builder().url(url).build())).results
  }

  private suspend fun clipFilm(q: FilmQuery, key: String): Result? {
    val words = FilmNames.words(q.title)
    if (words.size < 2) return null
    val queries =
        (q.title.split(" - ", " – ", ":").map { it.trim() }.filter { it.length > 2 } + words.take(2).joinToString(" ") + words.first())
            .distinct().take(4)
    val candidates = queries.flatMap { text -> runCatching { searchMovies(text, key) }.getOrDefault(emptyList()) }.distinctBy { it.id }
    return candidates.filter { FilmNames.clipOf(q, it.title, it.year) }.maxWithOrNull(compareBy<Result>({ FilmNames.words(it.title).size }, { it.popularity }))
  }

  private suspend fun art(r: Result, key: String, titled: Boolean, clip: Boolean): FilmArt {
    val lang = if (titled) "en" else null
    val pick =
        runCatching {
              val images = apiBase.newBuilder().addPathSegments("movie/${r.id}/images").addQueryParameter("api_key", key).addQueryParameter("include_image_language", "en,null").build()
              DwellJson.decodeFromString(Images.serializer(), http.fetch(Request.Builder().url(images).build()))
                  .backdrops.filter { it.language == lang }.maxByOrNull { it.votes }?.filePath
            }
            .getOrNull()
    return FilmArt(
        title = r.title,
        year = r.year,
        backdropUrl = (pick ?: r.backdropPath)?.let { "${imageBase}w780$it" },
        posterUrl = r.posterPath?.let { "${imageBase}w342$it" },
        clip = clip,
    )
  }

  private fun matchesResult(q: FilmQuery, r: Result): Boolean =
      FilmNames.matches(q, r.title, r.year) || (r.originalTitle != null && FilmNames.matches(q, r.originalTitle, r.year))

  @Serializable private data class Search(val results: List<Result> = emptyList())

  @Serializable private data class Images(val backdrops: List<Image> = emptyList())

  @Serializable
  private data class Image(
      @SerialName("file_path") val filePath: String,
      @SerialName("iso_639_1") val language: String? = null,
      @SerialName("vote_average") val votes: Double = 0.0,
  )

  @Serializable
  private data class Result(
      val id: Long,
      val title: String,
      @SerialName("original_title") val originalTitle: String? = null,
      @SerialName("release_date") val releaseDate: String? = null,
      @SerialName("backdrop_path") val backdropPath: String? = null,
      @SerialName("poster_path") val posterPath: String? = null,
      val popularity: Double = 0.0,
  ) {
    val year: Int?
      get() = releaseDate?.take(4)?.toIntOrNull()
  }
}
