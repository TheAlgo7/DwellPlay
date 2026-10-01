package com.thealgothrim.dwellplay.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import com.thealgothrim.dwellplay.core.model.StreamKind
import com.thealgothrim.dwellplay.core.model.Video
import java.util.concurrent.ConcurrentHashMap

/** Media3 items for native playback. YouTube never becomes one of these (see PlaybackDescriptor). */
object MediaItems {
  const val EXTRA_VIDEO_ID = "dwellplay.video"

  fun of(video: Video, d: PlaybackDescriptor.Native): MediaItem =
      MediaItem.Builder()
          .setMediaId(video.id)
          .setUri(d.uri)
          .setMimeType(d.mimeType)
          .setSubtitleConfigurations(
              d.subtitles.map {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                    .setMimeType(it.mimeType)
                    .setLanguage(it.language)
                    .setLabel(it.label)
                    .build()
              }
          )
          .setMediaMetadata(metadata(video, playable = true))
          .build()

  fun metadata(video: Video, playable: Boolean): MediaMetadata =
      MediaMetadata.Builder()
          .setTitle(video.title)
          .setDisplayTitle(video.title)
          .setArtist(video.subtitle)
          .setSubtitle(video.subtitle)
          // Car head units can't load artwork from a phone's content:// files; web artwork is fine.
          .setArtworkUri(video.artworkUrl?.takeIf { it.startsWith("http") }?.let(Uri::parse))
          .setIsPlayable(playable)
          .setIsBrowsable(!playable)
          .setMediaType(if (playable) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_FOLDER_VIDEOS)
          .setExtras(Bundle().apply { putString(EXTRA_VIDEO_ID, video.id) })
          .build()

  fun mimeFor(kind: StreamKind): String? =
      when (kind) {
        StreamKind.HLS -> MimeTypes.APPLICATION_M3U8
        StreamKind.DASH -> MimeTypes.APPLICATION_MPD
        StreamKind.PROGRESSIVE,
        StreamKind.AUTO -> null
      }
}

/**
 * Items the app hands the session. The session looks here first, so a playable item keeps its URI
 * and headers even if the controller strips them on the way over.
 */
object PendingItems {
  private val items = ConcurrentHashMap<String, MediaItem>()

  fun put(item: MediaItem) {
    items[item.mediaId] = item
  }

  fun get(mediaId: String): MediaItem? = items[mediaId]
}

/** Extra request headers for streams that need them (a token header on a personal stream), by host. */
object RequestHeaders {
  private val byHost = ConcurrentHashMap<String, Map<String, String>>()

  fun register(url: String, headers: Map<String, String>) {
    val host = Uri.parse(url).host ?: return
    if (headers.isEmpty()) byHost.remove(host) else byHost[host] = headers
  }

  fun forUri(uri: Uri): Map<String, String> = uri.host?.let { byHost[it] }.orEmpty()
}
