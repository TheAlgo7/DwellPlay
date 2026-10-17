package com.thealgothrim.dwellplay.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size as AndroidSize
import java.io.File
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
 * Draws a still for a local video, chosen from the video itself: five frames across it, keeping
 * the most vivid (bright, colourful, with contrast), so a clip shows its own scene instead of a
 * black opening or a title card. The choice is saved, so each video is decoded once. The system's
 * thumbnail is the fallback when the file can't be read.
 */
class VideoFrameFetcher(private val context: Context, private val data: VideoFrame, private val options: Options) : Fetcher {
  override suspend fun fetch(): FetchResult? {
    val uri = Uri.parse(data.uri)
    val w = options.size.width.pxOrElse { 640 }.coerceIn(96, 1280)
    val h = options.size.height.pxOrElse { 360 }.coerceIn(54, 720)
    val saved = File(File(context.cacheDir, "frames").apply { mkdirs() }, "${data.uri.hashCode().toUInt().toString(16)}-$w.jpg")
    val bitmap: Bitmap =
        saved.takeIf { it.length() > 0 }?.let { BitmapFactory.decodeFile(it.path) }
            ?: bestFrame(uri, w, h)?.also { b -> runCatching { saved.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } } }
            ?: runCatching { context.contentResolver.loadThumbnail(uri, AndroidSize(w, h), null) }.getOrNull()
            ?: return null
    return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
  }

  private fun bestFrame(uri: Uri, w: Int, h: Int): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
      if (durationUs <= 0) return retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
      listOf(0.12, 0.25, 0.4, 0.55, 0.7)
          .mapNotNull { f -> runCatching { retriever.getScaledFrameAtTime((durationUs * f).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) }.getOrNull() }
          .maxByOrNull { vividness(it) }
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

/** Contrast plus colour, minus a penalty for frames that are mostly black or blown out. */
private fun vividness(b: Bitmap): Double {
  val steps = 16
  var sum = 0.0
  var sumSq = 0.0
  var sat = 0.0
  val n = steps * steps
  for (i in 0 until steps) for (j in 0 until steps) {
    val c = b.getPixel((b.width - 1) * i / (steps - 1), (b.height - 1) * j / (steps - 1))
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val bl = c and 0xFF
    val luma = 0.299 * r + 0.587 * g + 0.114 * bl
    sum += luma
    sumSq += luma * luma
    val max = maxOf(r, g, bl)
    sat += if (max == 0) 0.0 else (max - minOf(r, g, bl)).toDouble() / max
  }
  val mean = sum / n
  val contrast = kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
  val exposure = when {
    mean < 35 -> (35 - mean) * 2
    mean > 215 -> (mean - 215) * 2
    else -> 0.0
  }
  return contrast + (sat / n) * 60 - exposure
}

class VideoFrameKeyer : Keyer<VideoFrame> {
  override fun key(data: VideoFrame, options: Options): String = "frame:${data.uri}"
}
