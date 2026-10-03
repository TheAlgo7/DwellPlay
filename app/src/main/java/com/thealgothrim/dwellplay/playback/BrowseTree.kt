package com.thealgothrim.dwellplay.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.sources.Library
import com.thealgothrim.dwellplay.sources.PhoneVideos
import com.thealgothrim.dwellplay.sources.STREAMS_SOURCE_ID

/**
 * What Android Auto's media screen lists while driving: sound only, so only the user's own media.
 * YouTube is left out entirely, since it may never play as background audio.
 */
class BrowseTree(private val library: Library) {

  fun root(): MediaItem = folder(ROOT, "DwellPlay")

  fun recentRoot(): MediaItem = folder(RECENT, "Recent")

  /** The last own-media title played (never YouTube), for "resume" controls. */
  fun lastPlayed(): Video? = library.history.value.firstOrNull { eligible(it) }

  fun children(parentId: String): List<MediaItem> =
      when (parentId) {
        RECENT -> listOfNotNull(lastPlayed()?.let { playable(it) })
        ROOT ->
            buildList {
              add(folder(CONTINUE, "Continue"))
              add(folder(PHONE, com.thealgothrim.dwellplay.sources.PhoneVideos.NAME))
              if (library.sources.value.isNotEmpty() || library.saved.value.any { it.type == SourceType.STREAM }) add(folder(MORE, "Servers and links"))
            }
        CONTINUE -> library.continueWatching.value.filter { eligible(it) }.map { playable(it) }
        PHONE -> library.phone.value.take(200).map { playable(it) }
        MORE ->
            buildList {
              library.sources.value.forEach { add(folder("$SOURCE_PREFIX${it.id}", it.name)) }
              if (library.saved.value.any { it.type == SourceType.STREAM }) add(folder("$SOURCE_PREFIX$STREAMS_SOURCE_ID", "Links"))
            }
        else ->
            if (parentId.startsWith(SOURCE_PREFIX)) library.videosOf(parentId.removePrefix(SOURCE_PREFIX)).filter { eligible(it) && !it.isFolder }.map { playable(it) }
            else emptyList()
      }

  fun item(mediaId: String): MediaItem? =
      when (mediaId) {
        ROOT -> root()
        CONTINUE,
        PHONE,
        MORE -> children(ROOT).firstOrNull { it.mediaId == mediaId }
        else -> library.find(mediaId)?.takeIf { eligible(it) }?.let { playable(it) }
      }

  private fun eligible(v: Video) = v.type != SourceType.YOUTUBE

  private fun playable(v: Video): MediaItem = MediaItem.Builder().setMediaId(v.id).setMediaMetadata(MediaItems.metadata(v, playable = true)).build()

  private fun folder(id: String, title: String): MediaItem =
      MediaItem.Builder()
          .setMediaId(id)
          .setMediaMetadata(
              MediaMetadata.Builder()
                  .setTitle(title)
                  .setIsBrowsable(true)
                  .setIsPlayable(false)
                  .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_VIDEOS)
                  .build()
          )
          .build()

  companion object {
    const val ROOT = "root"
    const val RECENT = "recent"
    const val CONTINUE = "continue"
    const val PHONE = PhoneVideos.SOURCE_ID + "-all"
    const val MORE = "more"
    const val SOURCE_PREFIX = "source:"
  }
}
