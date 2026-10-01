package com.thealgothrim.dwellplay.core.jellyfin

import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.SubtitleTrack
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.net.DwellJson
import com.thealgothrim.dwellplay.core.net.HttpException
import com.thealgothrim.dwellplay.core.net.fetch
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A signed-in server, with the token in the clear (only ever held in memory). */
data class JellyfinConnection(
    val sourceId: String,
    val baseUrl: String,
    val token: String,
    val userId: String,
    val serverId: String,
)

data class JellyfinLogin(
    val baseUrl: String,
    val token: String,
    val userId: String,
    val userName: String,
    val serverId: String,
    val serverName: String,
)

/** The server refused the saved sign-in (changed password, revoked device): sign in again. */
class JellyfinAuthException(message: String) : IOException(message)

/** What the car should play for one Jellyfin item. */
data class JellyfinPlayback(
    val url: String,
    val mimeType: String?,
    val subtitles: List<SubtitleTrack>,
    val mediaSourceId: String,
    val playSessionId: String?,
    /** "DirectPlay" (the original file) or "Transcode" (the server converts it to HLS). */
    val method: String,
)

/**
 * A small Jellyfin client: sign-in, libraries, search, and playback negotiation. The server is told
 * what this device decodes (the device profile) and answers with the original file when it can,
 * or an HLS stream it converts on the fly when it can't. Tested against Jellyfin 12.1.
 */
class JellyfinClient(
    private val http: OkHttpClient,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersion: String,
) {

  /** Server name and id, without signing in. Also how an address the user typed gets checked. */
  suspend fun publicInfo(baseUrl: String): PublicInfo =
      DwellJson.decodeFromString(PublicInfo.serializer(), http.fetch(Request.Builder().url("$baseUrl/System/Info/Public").build()))

  /**
   * Finds the server behind what the user typed ("192.168.1.5:8096", "jf.example.com",
   * "https://host/web/#/home"): https first, then http, with the web app's path stripped.
   */
  suspend fun locate(typed: String): Pair<String, PublicInfo> {
    val candidates = candidateBaseUrls(typed)
    if (candidates.isEmpty()) throw IOException("Enter the server's address.")
    var last: Exception? = null
    for (base in candidates) {
      try {
        val info = publicInfo(base)
        if (info.Id.isNotBlank()) return base to info
      } catch (e: Exception) {
        last = e
      }
    }
    throw IOException("No Jellyfin server answered at that address.", last)
  }

  suspend fun signIn(typed: String, username: String, password: String): JellyfinLogin {
    val (base, info) = locate(typed)
    val body = buildJsonObject {
      put("Username", username)
      put("Pw", password)
    }
    val request =
        Request.Builder()
            .url("$base/Users/AuthenticateByName")
            .header("Authorization", authHeader(null))
            .post(body.toString().toRequestBody(JSON))
            .build()
    val text =
        try {
          http.fetch(request)
        } catch (e: HttpException) {
          if (e.code == 401 || e.code == 400) throw JellyfinAuthException("Wrong user name or password.")
          throw e
        }
    val auth = DwellJson.decodeFromString(AuthResult.serializer(), text)
    return JellyfinLogin(
        baseUrl = base,
        token = auth.AccessToken,
        userId = auth.User.Id,
        userName = auth.User.Name,
        serverId = auth.ServerId ?: info.Id,
        serverName = info.ServerName ?: "Jellyfin",
    )
  }

  /** The user's libraries that hold video. */
  suspend fun views(c: JellyfinConnection): List<JfItem> =
      get(c, url(c, "UserViews") { addQueryParameter("userId", c.userId) }, ItemsResult.serializer()).Items.filter {
        it.CollectionType == null || it.CollectionType in VIDEO_VIEWS
      }

  /** A folder's children (a library, a series, a season), for browsing. */
  suspend fun children(c: JellyfinConnection, parentId: String, start: Int = 0, limit: Int = 100): List<JfItem> =
      get(
              c,
              url(c, "Items") {
                addQueryParameter("userId", c.userId)
                addQueryParameter("parentId", parentId)
                addQueryParameter("sortBy", "IsFolder,SortName")
                addQueryParameter("sortOrder", "Descending,Ascending")
                addQueryParameter("startIndex", start.toString())
                addQueryParameter("limit", limit.toString())
                addQueryParameter("fields", "ProductionYear")
                addQueryParameter("excludeItemTypes", "Audio,MusicAlbum,MusicArtist,Photo,PhotoAlbum,Book")
              },
              ItemsResult.serializer(),
          )
          .Items

  /** Newest videos across all libraries. */
  suspend fun latest(c: JellyfinConnection, limit: Int = 20): List<JfItem> =
      get(
              c,
              url(c, "Items") {
                addQueryParameter("userId", c.userId)
                addQueryParameter("recursive", "true")
                addQueryParameter("includeItemTypes", PLAYABLE_TYPES)
                addQueryParameter("sortBy", "DateCreated")
                addQueryParameter("sortOrder", "Descending")
                addQueryParameter("limit", limit.toString())
                addQueryParameter("fields", "ProductionYear")
              },
              ItemsResult.serializer(),
          )
          .Items

  /** What the server says the user was part-way through (on any device). */
  suspend fun resume(c: JellyfinConnection, limit: Int = 12): List<JfItem> =
      get(
              c,
              url(c, "UserItems/Resume") {
                addQueryParameter("userId", c.userId)
                addQueryParameter("limit", limit.toString())
                addQueryParameter("mediaTypes", "Video")
              },
              ItemsResult.serializer(),
          )
          .Items

  suspend fun search(c: JellyfinConnection, query: String, limit: Int = 30): List<JfItem> =
      get(
              c,
              url(c, "Items") {
                addQueryParameter("userId", c.userId)
                addQueryParameter("recursive", "true")
                addQueryParameter("searchTerm", query)
                addQueryParameter("includeItemTypes", "$PLAYABLE_TYPES,Series")
                addQueryParameter("limit", limit.toString())
                addQueryParameter("fields", "ProductionYear")
              },
              ItemsResult.serializer(),
          )
          .Items

  /**
   * Asks the server how to play [itemId] here. [convert] forces the server to transcode (the
   * "Ask the server to convert it" button when the car can't decode the original).
   */
  suspend fun playback(c: JellyfinConnection, itemId: String, convert: Boolean = false): JellyfinPlayback {
    val body = playbackRequest(convert)
    val request =
        Request.Builder()
            .url(url(c, "Items/$itemId/PlaybackInfo") { addQueryParameter("userId", c.userId) })
            .header("Authorization", authHeader(c.token))
            .post(body.toString().toRequestBody(JSON))
            .build()
    val info = DwellJson.decodeFromString(PlaybackInfo.serializer(), send(request))
    info.ErrorCode?.let { throw IOException("The server can't play this ($it).") }
    val source = info.MediaSources.firstOrNull() ?: throw IOException("The server has no playable file for this.")
    val base = c.baseUrl
    val subtitles =
        source.MediaStreams.filter { it.Type == "Subtitle" && it.DeliveryMethod == "External" && it.DeliveryUrl != null }.map {
          SubtitleTrack(
              url = withApiKey(base + it.DeliveryUrl, c.token),
              mimeType = if (it.DeliveryUrl!!.contains(".vtt", ignoreCase = true)) "text/vtt" else "application/x-subrip",
              language = it.Language,
              label = it.DisplayTitle ?: it.Language,
          )
        }
    return when {
      !convert && source.SupportsDirectPlay -> {
        val url =
            "$base/Videos/$itemId/stream".toHttpUrl().newBuilder()
                .addQueryParameter("static", "true")
                .addQueryParameter("mediaSourceId", source.Id)
                .apply { info.PlaySessionId?.let { addQueryParameter("playSessionId", it) } }
                .addQueryParameter("api_key", c.token)
                .build()
        JellyfinPlayback(url.toString(), null, subtitles, source.Id, info.PlaySessionId, "DirectPlay")
      }
      source.TranscodingUrl != null ->
          JellyfinPlayback(withApiKey(base + source.TranscodingUrl, c.token), MIME_HLS, subtitles, source.Id, info.PlaySessionId, "Transcode")
      else -> throw IOException("The server offered no format this device can play.")
    }
  }

  /** Tells the server playback started, so it shows up in its "Continue watching". */
  suspend fun reportStart(c: JellyfinConnection, itemId: String, mediaSourceId: String, playSessionId: String?, method: String, positionMs: Long) =
      report(c, "Sessions/Playing", itemId, mediaSourceId, playSessionId, method, positionMs, paused = false)

  suspend fun reportProgress(c: JellyfinConnection, itemId: String, mediaSourceId: String, playSessionId: String?, method: String, positionMs: Long, paused: Boolean) =
      report(c, "Sessions/Playing/Progress", itemId, mediaSourceId, playSessionId, method, positionMs, paused)

  suspend fun reportStopped(c: JellyfinConnection, itemId: String, mediaSourceId: String, playSessionId: String?, method: String, positionMs: Long) =
      report(c, "Sessions/Playing/Stopped", itemId, mediaSourceId, playSessionId, method, positionMs, paused = true)

  /** 16:9 art first (Thumb, then Backdrop), a poster last. */
  fun artworkUrl(c: JellyfinConnection, item: JfItem, maxWidth: Int = 720): String? {
    val (id, type, tag) =
        when {
          item.ImageTags["Thumb"] != null -> Triple(item.Id, "Thumb", item.ImageTags["Thumb"])
          item.BackdropImageTags.isNotEmpty() -> Triple(item.Id, "Backdrop/0", item.BackdropImageTags.first())
          item.Type == "Episode" && item.ParentThumbItemId != null && item.ParentThumbImageTag != null ->
              Triple(item.ParentThumbItemId, "Thumb", item.ParentThumbImageTag)
          item.ImageTags["Primary"] != null -> Triple(item.Id, "Primary", item.ImageTags["Primary"])
          else -> return null
        }
    return "${c.baseUrl}/Items/$id/Images/$type?maxWidth=$maxWidth&quality=85&tag=$tag"
  }

  fun toVideo(c: JellyfinConnection, item: JfItem): Video {
    val subtitle =
        when (item.Type) {
          "Episode" ->
              listOfNotNull(
                      item.SeriesName,
                      if (item.ParentIndexNumber != null && item.IndexNumber != null) "S${item.ParentIndexNumber} E${item.IndexNumber}" else null,
                  )
                  .joinToString(" · ")
                  .ifBlank { null }
          "MusicVideo" -> item.Artists.joinToString(", ").ifBlank { null } ?: item.ProductionYear?.toString()
          "Series" -> "Series"
          "Season" -> item.SeriesName
          else -> item.ProductionYear?.toString()
        }
    return Video(
        id = "jf:${c.sourceId}:${item.Id}",
        sourceId = c.sourceId,
        type = SourceType.JELLYFIN,
        title = item.Name,
        subtitle = subtitle,
        artworkUrl = artworkUrl(c, item),
        durationMs = item.RunTimeTicks?.let { it / 10_000 },
        ref = MediaRef.Jellyfin(c.sourceId, item.Id),
        isFolder = item.IsFolder,
    )
  }

  /** The server's own record of where the user stopped, in ms. */
  fun serverPositionMs(item: JfItem): Long? = item.UserData?.PlaybackPositionTicks?.takeIf { it > 0 }?.let { it / 10_000 }

  private suspend fun report(
      c: JellyfinConnection,
      path: String,
      itemId: String,
      mediaSourceId: String,
      playSessionId: String?,
      method: String,
      positionMs: Long,
      paused: Boolean,
  ) {
    val body = buildJsonObject {
      put("ItemId", itemId)
      put("MediaSourceId", mediaSourceId)
      playSessionId?.let { put("PlaySessionId", it) }
      put("PlayMethod", method)
      put("PositionTicks", positionMs * 10_000)
      put("IsPaused", paused)
      put("CanSeek", true)
    }
    val request =
        Request.Builder()
            .url(url(c, path) {})
            .header("Authorization", authHeader(c.token))
            .post(body.toString().toRequestBody(JSON))
            .build()
    runCatching { send(request) }
  }

  private suspend fun <T> get(c: JellyfinConnection, url: HttpUrl, serializer: kotlinx.serialization.KSerializer<T>): T {
    val request = Request.Builder().url(url).header("Authorization", authHeader(c.token)).build()
    return DwellJson.decodeFromString(serializer, send(request))
  }

  private suspend fun send(request: Request): String =
      try {
        http.fetch(request)
      } catch (e: HttpException) {
        // An expired or revoked sign-in fails closed: nothing plays until the user signs in again.
        if (e.code == 401 || e.code == 403) throw JellyfinAuthException("Sign in to this server again.")
        throw e
      }

  private fun url(c: JellyfinConnection, path: String, build: HttpUrl.Builder.() -> Unit): HttpUrl =
      "${c.baseUrl}/$path".toHttpUrl().newBuilder().apply(build).build()

  private fun authHeader(token: String?): String {
    val clean = { s: String -> s.replace("\"", "") }
    val base = "MediaBrowser Client=\"DwellPlay\", Device=\"${clean(deviceName)}\", DeviceId=\"${clean(deviceId)}\", Version=\"${clean(appVersion)}\""
    return if (token == null) base else "$base, Token=\"$token\""
  }

  companion object {
    const val MIME_HLS = "application/x-mpegURL"
    private val JSON = "application/json".toMediaType()
    const val PLAYABLE_TYPES = "Movie,Episode,MusicVideo,Video"
    private val VIDEO_VIEWS = setOf("movies", "tvshows", "musicvideos", "homevideos", "mixed", "boxsets", "playlists", "folders")

    fun candidateBaseUrls(typed: String): List<String> {
      var t = typed.trim().trimEnd('/')
      if (t.isEmpty()) return emptyList()
      // The web app's address: https://host/web/index.html#!/home.html -> https://host
      t = t.substringBefore('#').substringBefore("/web/").removeSuffix("/web").trimEnd('/')
      val withScheme = if (t.startsWith("http://", true) || t.startsWith("https://", true)) listOf(t) else listOf("https://$t", "http://$t")
      return withScheme.mapNotNull { it.toHttpUrlOrNull()?.toString()?.trimEnd('/') }.distinct()
    }

    private fun withApiKey(url: String, token: String): String {
      val parsed = url.toHttpUrlOrNull() ?: return url
      val has = parsed.queryParameterNames.any { it.equals("api_key", true) || it.equals("ApiKey", true) }
      return if (has) url else parsed.newBuilder().addQueryParameter("api_key", token).build().toString()
    }

    /** What ExoPlayer on a phone or head unit decodes. The server converts anything else. */
    fun playbackRequest(convert: Boolean): JsonObject = buildJsonObject {
      putJsonObject("DeviceProfile") {
        put("Name", "DwellPlay")
        put("MaxStreamingBitrate", 20_000_000)
        putJsonArray("DirectPlayProfiles") {
          add(
              buildJsonObject {
                put("Container", "mp4,m4v,mkv,webm,mov")
                put("Type", "Video")
                put("VideoCodec", "h264,hevc,vp9,av1")
                put("AudioCodec", "aac,mp3,opus,ac3,eac3,flac,vorbis")
              }
          )
        }
        putJsonArray("TranscodingProfiles") {
          add(
              buildJsonObject {
                put("Container", "ts")
                put("Type", "Video")
                put("VideoCodec", "h264")
                put("AudioCodec", "aac")
                put("Protocol", "hls")
                put("Context", "Streaming")
                put("MaxAudioChannels", "2")
                put("MinSegments", 1)
                put("BreakOnNonKeyFrames", true)
              }
          )
        }
        put(
            "SubtitleProfiles",
            buildJsonArray {
              add(buildJsonObject { put("Format", "vtt"); put("Method", "External") })
              add(buildJsonObject { put("Format", "srt"); put("Method", "External") })
            },
        )
      }
      put("AutoOpenLiveStream", true)
      put("EnableDirectPlay", !convert)
      put("EnableDirectStream", !convert)
      put("EnableTranscoding", true)
      put("AllowVideoStreamCopy", !convert)
      put("AllowAudioStreamCopy", true)
    }
  }
}

@Suppress("PropertyName")
@Serializable
data class PublicInfo(val Id: String = "", val ServerName: String? = null, val Version: String? = null)

@Suppress("PropertyName") @Serializable private data class AuthResult(val AccessToken: String, val ServerId: String? = null, val User: AuthUser)

@Suppress("PropertyName") @Serializable private data class AuthUser(val Id: String, val Name: String)

@Suppress("PropertyName") @Serializable private data class ItemsResult(val Items: List<JfItem> = emptyList(), val TotalRecordCount: Int = 0)

@Suppress("PropertyName")
@Serializable
data class JfItem(
    val Id: String,
    val Name: String = "",
    val Type: String = "",
    val IsFolder: Boolean = false,
    val CollectionType: String? = null,
    val RunTimeTicks: Long? = null,
    val ProductionYear: Int? = null,
    val SeriesName: String? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val Artists: List<String> = emptyList(),
    val ImageTags: Map<String, String> = emptyMap(),
    val BackdropImageTags: List<String> = emptyList(),
    val ParentThumbItemId: String? = null,
    val ParentThumbImageTag: String? = null,
    val UserData: JfUserData? = null,
)

@Suppress("PropertyName") @Serializable data class JfUserData(val PlaybackPositionTicks: Long? = null, val Played: Boolean = false)

@Suppress("PropertyName") @Serializable private data class PlaybackInfo(val MediaSources: List<JfMediaSource> = emptyList(), val PlaySessionId: String? = null, val ErrorCode: String? = null)

@Suppress("PropertyName")
@Serializable
private data class JfMediaSource(
    val Id: String,
    val Container: String? = null,
    val SupportsDirectPlay: Boolean = false,
    val SupportsDirectStream: Boolean = false,
    val TranscodingUrl: String? = null,
    val MediaStreams: List<JfStream> = emptyList(),
)

@Suppress("PropertyName")
@Serializable
private data class JfStream(
    val Type: String = "",
    val Codec: String? = null,
    val Language: String? = null,
    val DisplayTitle: String? = null,
    val DeliveryMethod: String? = null,
    val DeliveryUrl: String? = null,
)
