package com.thealgothrim.dwellplay.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a video comes from. The order is the order sources are listed in the app. */
@Serializable
enum class SourceType {
  /** Videos saved on the phone (MediaStore). On Android Auto the app runs on the phone, so these are local. */
  PHONE,
  /** A folder the user picked through the system file picker (a USB drive, SD card, or car storage). */
  FOLDER,
  /** YouTube, only ever through the official embedded player. */
  YOUTUBE,
  /** A personal Jellyfin server. */
  JELLYFIN,
  /** A direct HLS, DASH or file link the user owns. */
  STREAM,
}

/** A connected source. Phone, YouTube and Streams always exist; folders and servers are added. */
@Serializable
data class Source(
    val id: String,
    val type: SourceType,
    val name: String,
    /** FOLDER: the persisted document tree URI. */
    val folderUri: String? = null,
    /** JELLYFIN: the signed-in server. The access token is stored encrypted, see [JellyfinServer.sealedToken]. */
    val server: JellyfinServer? = null,
    val addedAtMs: Long = 0,
)

@Serializable
data class JellyfinServer(
    val baseUrl: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    /** The access token, sealed with a key that never leaves the phone's keystore. */
    val sealedToken: String,
)

/** One playable title in the library, whatever its source. */
@Serializable
data class Video(
    /** Stable across launches: "phone:12", "yt:dQw4w9WgXcQ", "jf:<source>:<item>", "stream:<uuid>". */
    val id: String,
    val sourceId: String,
    val type: SourceType,
    val title: String,
    /** Artist, channel, show or folder. */
    val subtitle: String? = null,
    /** http(s) image, or null to draw a frame from the file itself. */
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val ref: MediaRef,
    val addedAtMs: Long = 0,
    /** Width / height when known, for laying out the theater. */
    val aspect: Float? = null,
    /** True for a Jellyfin folder, series or season: opening it browses instead of playing. */
    val isFolder: Boolean = false,
)

/** How to find the bytes (or the player) for a [Video]. Resolved into a [PlaybackDescriptor] at play time. */
@Serializable
sealed interface MediaRef {
  /** A content:// URI on this device (MediaStore or a picked folder). */
  @Serializable
  @SerialName("content")
  data class Content(val uri: String, val mimeType: String? = null) : MediaRef

  /** A link the user added. */
  @Serializable
  @SerialName("stream")
  data class Stream(
      val url: String,
      val kind: StreamKind = StreamKind.AUTO,
      val headers: Map<String, String> = emptyMap(),
  ) : MediaRef

  /** A YouTube video or playlist. There is deliberately no URL here: YouTube plays only in its own player. */
  @Serializable
  @SerialName("youtube")
  data class YouTube(
      val videoId: String? = null,
      val playlistId: String? = null,
      val startSeconds: Int = 0,
  ) : MediaRef

  /** An item on a signed-in Jellyfin server. */
  @Serializable
  @SerialName("jellyfin")
  data class Jellyfin(val sourceId: String, val itemId: String) : MediaRef
}

@Serializable
enum class StreamKind {
  AUTO,
  HLS,
  DASH,
  PROGRESSIVE,
}

/** A subtitle file served next to the video (Jellyfin sends these as WebVTT). */
data class SubtitleTrack(val url: String, val mimeType: String, val language: String?, val label: String?)

/**
 * What the player actually needs. The two shapes are separate on purpose (see
 * docs/architecture.md): a YouTube video id can never be handed to the native player, so nobody can
 * later route YouTube through an extractor, strip its ads or play it as background audio.
 */
sealed interface PlaybackDescriptor {
  /** The library id, used to remember the position. */
  val videoId: String

  /** Played by Media3/ExoPlayer: phone files, folders, streams and personal servers. */
  data class Native(
      override val videoId: String,
      val uri: String,
      val mimeType: String?,
      val headers: Map<String, String> = emptyMap(),
      val subtitles: List<SubtitleTrack> = emptyList(),
      /** Whether this may keep playing as audio when the video has to disappear (driving). */
      val drivingAudioEligible: Boolean,
      /** A personal server that can convert the file if this car can't decode it. */
      val canAskServerToConvert: Boolean = false,
      /** Jellyfin's session, so progress can be reported back to the server. */
      val serverSession: ServerPlayback? = null,
  ) : PlaybackDescriptor

  /** Played only by the official YouTube IFrame player in a visible WebView. */
  data class YouTube(
      override val videoId: String,
      val youtubeVideoId: String?,
      val playlistId: String?,
      val startSeconds: Int,
  ) : PlaybackDescriptor
}

/** Enough to tell a Jellyfin server what was watched. */
data class ServerPlayback(
    val sourceId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String?,
    val method: String,
)
