package com.thealgothrim.dwellplay.core.links

import com.thealgothrim.dwellplay.core.model.StreamKind
import com.thealgothrim.dwellplay.core.net.LocalNetwork
import java.net.URI
import java.net.URLDecoder

/** A YouTube link, as shared from the YouTube or YouTube Music app, or pasted. */
data class YouTubeLink(val videoId: String?, val playlistId: String?, val startSeconds: Int = 0)

/** What a shared or pasted piece of text turned out to be. */
sealed interface LinkKind {
  data class YouTube(val link: YouTubeLink) : LinkKind
  data class Stream(val url: String, val kind: StreamKind) : LinkKind
  data class Unsupported(val reason: String) : LinkKind
}

object Links {
  private val ID = Regex("^[A-Za-z0-9_-]{11}$")
  private val LIST = Regex("^[A-Za-z0-9_-]{10,64}$")
  private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
  private val YOUTUBE_HOSTS =
      setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be", "www.youtu.be")

  /**
   * Streaming services whose players are the only lawful way to watch them. DwellPlay never tries to
   * play their pages (see docs/architecture.md).
   */
  private val OFFICIAL_APP_ONLY =
      mapOf(
          "netflix.com" to "Netflix",
          "primevideo.com" to "Prime Video",
          "amazon.com" to "Prime Video",
          "hotstar.com" to "JioHotstar",
          "jiohotstar.com" to "JioHotstar",
          "sonyliv.com" to "SonyLIV",
          "zee5.com" to "ZEE5",
          "disneyplus.com" to "Disney+",
          "spotify.com" to "Spotify",
      )

  /** The first link in [text] (share text often carries a title before the link). */
  fun firstUrl(text: String): String? = URL_IN_TEXT.find(text.trim())?.value?.trimEnd('.', ',', ')', ']')

  fun classify(text: String): LinkKind {
    val url = firstUrl(text) ?: return LinkKind.Unsupported("That doesn't look like a link.")
    val uri = runCatching { URI(url) }.getOrNull() ?: return LinkKind.Unsupported("That link can't be read.")
    val host = uri.host?.lowercase() ?: return LinkKind.Unsupported("That link has no address.")
    if (host in YOUTUBE_HOSTS) {
      val link = parseYouTube(url) ?: return LinkKind.Unsupported("That YouTube link has no video or playlist in it.")
      return LinkKind.YouTube(link)
    }
    OFFICIAL_APP_ONLY.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.let {
      return LinkKind.Unsupported("${it.value} plays only in its own app.")
    }
    // Plain http only on the local network, so nothing someone watches crosses the internet unencrypted.
    if (!LocalNetwork.allowed(url)) return LinkKind.Unsupported("Use the link's https address. Plain http works only on your own network.")
    return LinkKind.Stream(url, streamKind(url))
  }

  fun parseYouTube(url: String): YouTubeLink? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in YOUTUBE_HOSTS) return null
    val q = query(uri.rawQuery)
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    val fromPath =
        when {
          host.endsWith("youtu.be") -> segments.firstOrNull()
          segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live", "v", "e") -> segments[1]
          else -> null
        }
    val videoId = (q["v"] ?: fromPath)?.takeIf { ID.matches(it) }
    // Mixes ("RD...") are generated per person and can't be embedded as playlists.
    val playlistId = q["list"]?.takeIf { LIST.matches(it) && !it.startsWith("RD") }
    if (videoId == null && playlistId == null) return null
    val start = parseStart(q["t"] ?: q["start"] ?: q["time_continue"])
    return YouTubeLink(videoId, playlistId, start)
  }

  /** "90", "90s", "1m30s", "1h2m3s" -> seconds. */
  fun parseStart(raw: String?): Int {
    if (raw.isNullOrBlank()) return 0
    raw.toIntOrNull()?.let { return it.coerceAtLeast(0) }
    val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(raw.lowercase()) ?: return 0
    val (h, min, s) = m.destructured
    return (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
  }

  /** Guesses the stream type from the path. Media3 sniffs files anyway; manifests need the hint. */
  fun streamKind(url: String): StreamKind {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
    return when {
      path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamKind.HLS
      path.endsWith(".mpd") -> StreamKind.DASH
      path.substringAfterLast('.', "") in setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "mp3", "m4a", "aac", "ogg", "flac", "3gp") -> StreamKind.PROGRESSIVE
      else -> StreamKind.AUTO
    }
  }

  /** A readable default name for a link the user didn't name. */
  fun defaultName(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val file = uri.path.orEmpty().substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
    val generic = setOf("", "index", "master", "playlist", "manifest", "stream", "live", "chunklist")
    return if (file.lowercase() in generic) (uri.host ?: url) else file
  }

  private fun query(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split('&').mapNotNull {
      val i = it.indexOf('=')
      if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()
  }
}
