package com.thealgothrim.dwellplay.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.thealgothrim.dwellplay.AppGraph
import com.thealgothrim.dwellplay.BuildConfig
import com.thealgothrim.dwellplay.MainActivity
import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * The player lives here so sound can outlive the screen: a media notification on the phone, the
 * media screen in Android Auto while driving, and background audio on Android Automotive OS cars
 * that allow it. The screens talk to it through a MediaController (PlayerHub).
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
  private var session: MediaLibrarySession? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onCreate() {
    super.onCreate()
    AppGraph.init(application)
    val http = OkHttpDataSource.Factory(AppGraph.http).setUserAgent("DwellPlay/${BuildConfig.VERSION_NAME}")
    val upstream = DefaultDataSource.Factory(this, http)
    val withHeaders = ResolvingDataSource.Factory(upstream) { spec ->
      val extra = RequestHeaders.forUri(spec.uri)
      if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
    }
    val player =
        ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(withHeaders))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    val open =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setSessionActivity(open).build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Swiped away from recents with nothing playing: go. Playing sound keeps going, like any music app.
    val p = session?.player
    if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
      pauseAllPlayersAndStopSelf()
    }
  }

  override fun onDestroy() {
    scope.cancel()
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }

  private inner class LibraryCallback : MediaLibrarySession.Callback {
    private val tree = BrowseTree(AppGraph.library)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        // The phone's "resume media" controls ask for a recent root: just the last thing played.
        Futures.immediateFuture(LibraryResult.ofItem(if (params?.isRecent == true) tree.recentRoot() else tree.root(), params))

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
          val last = tree.lastPlayed() ?: throw UnsupportedOperationException("Nothing to resume")
          val item = playable(MediaItem.Builder().setMediaId(last.id).build()) ?: throw UnsupportedOperationException("Can't resume ${last.id}")
          MediaSession.MediaItemsWithStartPosition(listOf(item), 0, AppGraph.hub.resumePositionFor(last.id) ?: C.TIME_UNSET)
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future {
          val all = tree.children(parentId)
          val from = (page * pageSize).coerceAtMost(all.size)
          val to = (from + pageSize).coerceAtMost(all.size)
          LibraryResult.ofItemList(all.subList(from, to), params)
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        )

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future { mediaItems.mapNotNull { playable(it) } }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future {
          val items = mediaItems.mapNotNull { playable(it) }
          // Picked from the car's media screen: start where it was left.
          val start =
              if (startPositionMs == C.TIME_UNSET && items.size == 1) AppGraph.hub.resumePositionFor(items[0].mediaId) ?: C.TIME_UNSET
              else startPositionMs
          MediaSession.MediaItemsWithStartPosition(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), start)
        }

    /** The item with its URI: from the app's own hand-off, or resolved from the library by id. */
    private suspend fun playable(item: MediaItem): MediaItem? {
      if (item.localConfiguration != null) return item
      PendingItems.get(item.mediaId)?.let { return it }
      val video = AppGraph.library.find(item.mediaId) ?: return null
      return try {
        when (val d = AppGraph.resolver.resolve(video)) {
          is PlaybackDescriptor.Native -> MediaItems.of(video, d).also { PendingItems.put(it) }
          // YouTube plays only in its own visible player, never through this service.
          is PlaybackDescriptor.YouTube -> null
        }
      } catch (e: Exception) {
        Log.w(TAG, "Can't resolve ${item.mediaId}", e)
        null
      }
    }
  }

  private companion object {
    const val TAG = "PlaybackService"
  }
}
