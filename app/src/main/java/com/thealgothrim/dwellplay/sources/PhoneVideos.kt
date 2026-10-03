package com.thealgothrim.dwellplay.sources

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.thealgothrim.dwellplay.BuildConfig
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Videos saved on the phone. On Android Auto the app runs on the phone, so these play directly on
 * the car screen: no bridge, no copying (the report's "phase two" problem only exists on
 * Android Automotive OS, where the app runs in the car).
 */
object PhoneVideos {
  const val SOURCE_ID = "phone"

  enum class Access {
    ALL,
    /** Android 14+: the user picked "Select photos and videos". */
    SOME,
    NONE,
  }

  fun permissions(): Array<String> =
      when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
      }

  fun access(context: Context): Access {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
      Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.ALL
      Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SOME
      Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.ALL
      else -> Access.NONE
    }
  }

  /**
   * The one folder DwellPlay shows by itself, the way Picsart keeps its own Fonts folder: Movies ›
   * DwellPlay. The rest of the phone stays out of sight unless it is added as a folder in Sources,
   * so passengers never see someone's personal videos on the car screen.
   */
  const val FOLDER = "Movies/DwellPlay/"
  const val FOLDER_LABEL = "Movies › DwellPlay"
  const val NAME = "DwellPlay folder"

  suspend fun scan(context: Context): List<Video> =
      withContext(Dispatchers.IO) {
        if (access(context) == Access.NONE) return@withContext emptyList()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection =
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.ARTIST,
            )
        val out = ArrayList<Video>()
        val only = testFiles(context)
        val selection =
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" +
                if (only.isEmpty()) "" else " AND ${MediaStore.Video.Media.DISPLAY_NAME} IN (${only.joinToString(",") { "?" }})"
        val args = arrayOf("$FOLDER%") + only
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
          val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
          val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
          val title = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
          val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
          val bucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
          val added = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
          val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
          val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
          val mime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
          val artist = c.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
          while (c.moveToNext()) {
            val rowId = c.getLong(id)
            val uri = ContentUris.withAppendedId(collection, rowId)
            val w = c.getInt(width)
            val h = c.getInt(height)
            val folder = c.getString(bucket)
            val by = c.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
            out +=
                Video(
                    id = "phone:$rowId",
                    sourceId = SOURCE_ID,
                    type = SourceType.PHONE,
                    title = niceTitle(c.getString(title), c.getString(name)),
                    subtitle = by ?: folder,
                    durationMs = c.getLong(duration).takeIf { it > 0 },
                    ref = MediaRef.Content(uri.toString(), c.getString(mime)),
                    addedAtMs = c.getLong(added) * 1000,
                    aspect = if (w > 0 && h > 0) w.toFloat() / h else null,
                )
          }
        }
        out
      }

  /**
   * Debug builds only: tests on someone's own phone can limit the app to named test files (the
   * DebugReceiver "only" command), so screens and screenshots never show their personal videos.
   */
  fun testFiles(context: Context): List<String> =
      if (!BuildConfig.DEBUG) emptyList()
      else context.getSharedPreferences("debug", Context.MODE_PRIVATE).getString("only_files", null)?.split('|')?.filter { it.isNotBlank() }.orEmpty()

  /** "VID_20260912_184501" stays as is; "Coldplay_-_Yellow.mp4" becomes "Coldplay - Yellow". */
  fun niceTitle(title: String?, displayName: String?): String {
    val base = title?.takeIf { it.isNotBlank() } ?: displayName?.substringBeforeLast('.') ?: "Video"
    return base.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { "Video" }
  }
}
