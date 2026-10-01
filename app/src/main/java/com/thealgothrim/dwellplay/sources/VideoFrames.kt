package com.thealgothrim.dwellplay.sources

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size as AndroidSize
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse

/** Ask Coil for a still from a video on this device (a phone or folder video has no artwork URL). */
data class VideoFrame(val uri: String)

/**
 * Draws a still for a local video: the system's own thumbnail first (fast, cached by MediaStore),
 * then a frame a tenth of the way in.
 */
class VideoFrameFetcher(private val context: Context, private val data: VideoFrame, private val options: Options) : Fetcher {
  override suspend fun fetch(): FetchResult? {
    val uri = Uri.parse(data.uri)
    val w = options.size.width.pxOrElse { 640 }.coerceIn(96, 1280)
    val h = options.size.height.pxOrElse { 360 }.coerceIn(54, 720)
    val bitmap: Bitmap =
        runCatching { context.contentResolver.loadThumbnail(uri, AndroidSize(w, h), null) }.getOrNull()
            ?: frameFromFile(uri, w, h)
            ?: return null
    return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
  }

  private fun frameFromFile(uri: Uri, w: Int, h: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
      retriever.getScaledFrameAtTime(durationUs / 10, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
    } catch (e: Exception) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  class Factory(private val context: Context) : Fetcher.Factory<VideoFrame> {
    override fun create(data: VideoFrame, options: Options, imageLoader: ImageLoader): Fetcher = VideoFrameFetcher(context, data, options)
  }
}

class VideoFrameKeyer : Keyer<VideoFrame> {
  override fun key(data: VideoFrame, options: Options): String = "frame:${data.uri}"
}
