package com.thealgothrim.dwellplay.core.youtube

import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.net.DwellJson
import com.thealgothrim.dwellplay.core.net.HttpException
import com.thealgothrim.dwellplay.core.net.fetch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

const val YOUTUBE_SOURCE_ID = "youtube"

/**
 * Titles and search for YouTube. Metadata only: playback always goes through the official IFrame
 * player. Without an API key, shared links still work (oEmbed gives the title and channel);
 * with a YouTube Data API key, search works too.
 */
class YouTubeCatalog(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val oembedBase: HttpUrl = "https://www.youtube.com/oembed".toHttpUrl(),
    private val apiBase: HttpUrl = "https://www.googleapis.com/youtube/v3/".toHttpUrl(),
) {

  val canSearch: Boolean
    get() = !apiKey().isNullOrBlank()

  /** Builds a library entry for a shared video or playlist, with its real title when YouTube answers. */
  suspend fun describe(videoId: String?, playlistId: String?, startSeconds: Int = 0, now: Long = System.currentTimeMillis()): Video {
    val pageUrl =
        if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else "https://www.youtube.com/playlist?list=$playlistId"
    val meta =
        runCatching {
              val url = oembedBase.newBuilder().addQueryParameter("url", pageUrl).addQueryParameter("format", "json").build()
              DwellJson.decodeFromString(OEmbed.serializer(), http.fetch(Request.Builder().url(url).build()))
            }
            .getOrNull()
    val id = if (videoId != null) "yt:$videoId" else "ytpl:$playlistId"
    return Video(
        id = id,
        sourceId = YOUTUBE_SOURCE_ID,
        type = SourceType.YOUTUBE,
        title = meta?.title ?: if (videoId != null) PLACEHOLDER_VIDEO else PLACEHOLDER_PLAYLIST,
        subtitle = meta?.authorName?.let { if (videoId == null) "Playlist · $it" else it } ?: if (videoId == null) "Playlist" else null,
        artworkUrl = videoId?.let { thumbnail(it) } ?: meta?.thumbnailUrl,
        ref = MediaRef.YouTube(videoId, playlistId, startSeconds),
        addedAtMs = now,
        aspect = 16f / 9f,
    )
  }

  /** Embeddable videos matching [query]. Needs an API key (100 quota units a search, 10,000 a day free). */
  suspend fun search(query: String, max: Int = 24): List<Video> {
    val key = apiKey()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val url =
        apiBase.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            // Only videos whose owners allow embedding: the rest would just show an error in the car.
            .addQueryParameter("videoEmbeddable", "true")
            .addQueryParameter("maxResults", max.coerceIn(1, 50).toString())
            .addQueryParameter("q", query)
            .addQueryParameter("key", key)
            .build()
    val result = DwellJson.decodeFromString(SearchResponse.serializer(), fetchApi(url))
    val ids = result.items.mapNotNull { it.id.videoId }
    val durations = if (ids.isEmpty()) emptyMap() else durations(ids, key)
    return result.items.mapNotNull { item ->
      val vid = item.id.videoId ?: return@mapNotNull null
      Video(
          id = "yt:$vid",
          sourceId = YOUTUBE_SOURCE_ID,
          type = SourceType.YOUTUBE,
          title = decodeEntities(item.snippet.title),
          subtitle = decodeEntities(item.snippet.channelTitle),
          artworkUrl = thumbnail(vid),
          durationMs = durations[vid],
          ref = MediaRef.YouTube(vid),
          aspect = 16f / 9f,
      )
    }
  }

  private suspend fun durations(ids: List<String>, key: String): Map<String, Long> =
      runCatching {
            val url =
                apiBase.newBuilder()
                    .addPathSegment("videos")
                    .addQueryParameter("part", "contentDetails")
                    .addQueryParameter("id", ids.joinToString(","))
                    .addQueryParameter("key", key)
                    .build()
            DwellJson.decodeFromString(VideosResponse.serializer(), fetchApi(url)).items.associate {
              it.id to (Iso8601.durationMs(it.contentDetails.duration) ?: 0L)
            }
          }
          .getOrDefault(emptyMap())

  private suspend fun fetchApi(url: HttpUrl): String =
      try {
        http.fetch(Request.Builder().url(url).build())
      } catch (e: HttpException) {
        throw when (e.code) {
          400,
          403 -> YouTubeApiException("YouTube refused the API key (quota used up or key restricted).", e)
          else -> e
        }
      }

  companion object {
    /** Titles used when YouTube couldn't be reached; the library fetches the real one later. */
    const val PLACEHOLDER_VIDEO = "YouTube video"
    const val PLACEHOLDER_PLAYLIST = "YouTube playlist"

    fun isPlaceholder(title: String) = title == PLACEHOLDER_VIDEO || title == PLACEHOLDER_PLAYLIST

    /** 480x360 with bars; drawn cropped to 16:9 it shows exactly the picture. Always exists. */
    fun thumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    fun decodeEntities(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
  }
}

class YouTubeApiException(message: String, cause: Throwable?) : Exception(message, cause)

@Serializable
private data class OEmbed(
    val title: String? = null,
    @kotlinx.serialization.SerialName("author_name") val authorName: String? = null,
    @kotlinx.serialization.SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable private data class SearchResponse(val items: List<SearchItem> = emptyList())

@Serializable private data class SearchItem(val id: SearchId, val snippet: Snippet)

@Serializable private data class SearchId(val videoId: String? = null)

@Serializable private data class Snippet(val title: String = "", val channelTitle: String = "")

@Serializable private data class VideosResponse(val items: List<VideoItem> = emptyList())

@Serializable private data class VideoItem(val id: String, val contentDetails: ContentDetails)

@Serializable private data class ContentDetails(val duration: String = "")

/** YouTube's "PT4M13S" durations. */
object Iso8601 {
  private val RE = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

  fun durationMs(s: String): Long? {
    val m = RE.matchEntire(s) ?: return null
    val (d, h, min, sec) = m.destructured
    val total = (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
    return total * 1000
  }
}
