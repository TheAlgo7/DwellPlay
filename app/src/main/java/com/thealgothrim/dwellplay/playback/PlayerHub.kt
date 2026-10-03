package com.thealgothrim.dwellplay.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.thealgothrim.dwellplay.car.CarScreens
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinAuthException
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinClient
import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import com.thealgothrim.dwellplay.core.model.ServerPlayback
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.net.HttpException
import com.thealgothrim.dwellplay.core.policy.HiddenPlayback
import com.thealgothrim.dwellplay.core.policy.HideReason
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.core.policy.SafetyMachine
import com.thealgothrim.dwellplay.core.policy.SafetyMachine.Effect
import com.thealgothrim.dwellplay.core.policy.SafetyMachine.Event
import com.thealgothrim.dwellplay.core.policy.Screen
import com.thealgothrim.dwellplay.data.Settings
import com.thealgothrim.dwellplay.sources.Library
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One coordinator for both players (Media3 for everything native, the YouTube embed for YouTube)
 * and the safety rules between them. Screens report when they're visible; the [SafetyMachine]
 * decides what playback may do; this class carries it out.
 */
class PlayerHub(
    private val context: Context,
    private val library: Library,
    private val resolver: Resolver,
    private val settings: Settings,
    private val jellyfin: JellyfinClient,
    private val scope: CoroutineScope,
) {
  data class NowPlaying(val video: Video, val descriptor: PlaybackDescriptor?, val queue: List<Video>, val index: Int) {
    val hasNext: Boolean
      get() = index < queue.size - 1

    val hasPrevious: Boolean
      get() = index > 0

    val isYouTube: Boolean
      get() = video.type == SourceType.YOUTUBE
  }

  data class PlayerUi(
      val playing: Boolean = false,
      val buffering: Boolean = false,
      val positionMs: Long = 0,
      val durationMs: Long = 0,
      val videoAspect: Float? = null,
      val captionsAvailable: Boolean = false,
  )

  data class Problem(
      val title: String,
      val detail: String? = null,
      val canRetry: Boolean = true,
      /** A Jellyfin original the car can't decode: the server can transcode it. */
      val canConvert: Boolean = false,
      val signIn: Boolean = false,
      /** YouTube owners who block embedding: only the YouTube app can play it. */
      val openInYouTube: Boolean = false,
  )

  /** What the YouTube embed should have loaded. Null means unloaded (stopped, never playing in the background). */
  data class YouTubeRequest(
      val videoId: String?,
      val playlistId: String?,
      val startSeconds: Int,
      val autoplay: Boolean,
      val captions: Boolean,
      val nonce: Long,
      val playlistIndex: Int = 0,
  )

  /** The embed on screen, registered by its composable. */
  interface YouTubeHandle {
    fun play()

    fun pause()

    fun seekTo(seconds: Double)

    fun stop()

    fun next()

    fun previous()

    fun captions(on: Boolean)
  }

  private val _now = MutableStateFlow<NowPlaying?>(null)
  val now: StateFlow<NowPlaying?> = _now.asStateFlow()
  private val _ui = MutableStateFlow(PlayerUi())
  val ui: StateFlow<PlayerUi> = _ui.asStateFlow()
  private val _problem = MutableStateFlow<Problem?>(null)
  val problem: StateFlow<Problem?> = _problem.asStateFlow()
  private val _loading = MutableStateFlow(false)
  val loading: StateFlow<Boolean> = _loading.asStateFlow()
  private val _safety = MutableStateFlow<SafetyMachine.State>(SafetyMachine.State.Idle)
  val safety: StateFlow<SafetyMachine.State> = _safety.asStateFlow()
  private val _youtube = MutableStateFlow<YouTubeRequest?>(null)
  val youtube: StateFlow<YouTubeRequest?> = _youtube.asStateFlow()
  private val _controller = MutableStateFlow<MediaController?>(null)
  val controller: StateFlow<MediaController?> = _controller.asStateFlow()

  @Volatile private var vehicleAudio = true
  private val machine = SafetyMachine({ vehicleAudio }, { settings.current.soundWhenHidden })
  private var youtubeHandle: YouTubeHandle? = null
  private var nonce = 0L
  private var ytPositionMs = 0L
  private var ytDurationMs = 0L
  private var ytPlaylistIndex = 0
  private var serverSession: ServerPlayback? = null
  private var connecting = false
  private var startJob: Job? = null
  private var lastSave = 0L
  private var lastReport = 0L

  init {
    scope.launch {
      while (true) {
        delay(500)
        tick()
      }
    }
  }

  // ---- Media3 connection ----

  @androidx.annotation.OptIn(UnstableApi::class)
  fun connect() {
    if (_controller.value != null || connecting) return
    connecting = true
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val future = MediaController.Builder(context, token).buildAsync()
    future.addListener(
        {
          connecting = false
          runCatching { future.get() }
              .onSuccess { c ->
                c.addListener(listener)
                _controller.value = c
              }
              .onFailure { Log.w(TAG, "Player service unavailable", it) }
        },
        ContextCompat.getMainExecutor(context),
    )
  }

  private suspend fun awaitController(): MediaController {
    connect()
    return withTimeout(10_000) { _controller.filterNotNull().first() }
  }

  // ---- Windows (screens) ----

  private data class Window(val screen: Screen, val visible: Boolean, val restricted: Boolean)

  private val windows = LinkedHashMap<Int, Window>()
  private var shown = false
  /** Debug builds: pretend every screen is under driving restrictions (the adb "drive" hook). */
  private var debugDriving = false

  fun debugDriving(on: Boolean) {
    debugDriving = on
    recompute()
  }

  /** An activity's visibility on its screen. [restricted]: Android Automotive OS driving restrictions are on. */
  fun reportWindow(id: Int, screen: Screen, visible: Boolean, restricted: Boolean) {
    windows[id] = Window(screen, visible, restricted)
    recompute()
  }

  fun forgetWindow(id: Int) {
    windows.remove(id)
    recompute()
  }

  private fun recompute() {
    vehicleAudio = windows.values.all { CarScreens.vehicleAllowsBackgroundAudio(context, it.screen) }
    val nowShown = !debugDriving && windows.values.any { it.visible && !it.restricted }
    if (nowShown == shown) return
    shown = nowShown
    if (nowShown) {
      apply(machine.on(Event.Show))
    } else {
      // Android Auto takes the car screen away when the car moves; treat any hide there as driving.
      val driving = debugDriving || windows.values.any { it.restricted || it.screen != Screen.PHONE }
      apply(machine.on(Event.Hide(if (driving) HideReason.DRIVING else HideReason.BACKGROUND)))
    }
  }

  // ---- Playing ----

  fun play(video: Video, queue: List<Video> = listOf(video)) {
    if (video.isFolder) return
    val list = queue.filter { !it.isFolder }.ifEmpty { listOf(video) }
    val index = list.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
    launchStart(list[index], list, index, convert = false, startAtMs = null)
  }

  /** A voice command starts native media without dismissing a post-drive picture gate. */
  fun playFromSearch(video: Video) {
    if (video.isFolder || video.type == SourceType.YOUTUBE) return
    launchStart(video, listOf(video), 0, convert = false, startAtMs = null, automatic = true)
  }

  fun next() {
    val np = _now.value ?: return
    val d = np.descriptor
    if (d is PlaybackDescriptor.YouTube && d.playlistId != null && !np.hasNext) {
      youtubeHandle?.next()
      return
    }
    if (np.hasNext) launchStart(np.queue[np.index + 1], np.queue, np.index + 1, convert = false, startAtMs = null)
  }

  fun previous() {
    val np = _now.value ?: return
    val d = np.descriptor
    if (_ui.value.positionMs > 5_000 || !np.hasPrevious) {
      if (d is PlaybackDescriptor.YouTube && d.playlistId != null && _ui.value.positionMs <= 5_000) youtubeHandle?.previous() else seekTo(0)
      return
    }
    launchStart(np.queue[np.index - 1], np.queue, np.index - 1, convert = false, startAtMs = null)
  }

  fun retry() {
    val np = _now.value ?: return
    launchStart(np.video, np.queue, np.index, convert = false, startAtMs = currentPositionMs().takeIf { it > 0 })
  }

  /** A Jellyfin original this car can't decode: ask the server to convert it as it plays. */
  fun askServerToConvert() {
    val np = _now.value ?: return
    launchStart(np.video, np.queue, np.index, convert = true, startAtMs = currentPositionMs().takeIf { it > 0 })
  }

  private fun launchStart(video: Video, queue: List<Video>, index: Int, convert: Boolean, startAtMs: Long?, automatic: Boolean = false) {
    startJob?.cancel()
    startJob = scope.launch { start(video, queue, index, convert, startAtMs, automatic) }
  }

  private suspend fun start(video: Video, queue: List<Video>, index: Int, convert: Boolean, startAtMs: Long?, automatic: Boolean) {
    savePosition()
    reportServerStopped()
    _problem.value = null
    _loading.value = true
    _now.value = NowPlaying(video, null, queue, index)
    _ui.value = PlayerUi(durationMs = video.durationMs ?: 0, videoAspect = video.aspect)
    val d =
        try {
          resolver.resolve(video, convert)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Can't resolve ${video.id}", e)
          _loading.value = false
          _problem.value = problemFor(e, video)
          return
        }
    _now.value = NowPlaying(video, d, queue, index)
    library.markPlayed(video)
    var effects = machine.on(Event.Load(d, automatic = automatic))
    // A first load may arrive while the activity is already restricted: Idle has no descriptor
    // for the earlier Hide event. Apply the current window policy before publishing playback.
    if (!shown && machine.state is SafetyMachine.State.Watching) {
      val driving = debugDriving || windows.values.any { it.restricted || it.screen != Screen.PHONE }
      effects = machine.on(Event.Hide(if (driving) HideReason.DRIVING else HideReason.BACKGROUND))
    }
    when (d) {
      is PlaybackDescriptor.Native -> {
        youtubeHandle?.stop()
        _youtube.value = null
        val c =
            try {
              awaitController()
            } catch (e: Exception) {
              _loading.value = false
              _problem.value = Problem("The player didn't start", "Close DwellPlay and open it again.")
              return
            }
        RequestHeaders.register(d.uri, d.headers)
        val item = MediaItems.of(video, d)
        PendingItems.put(item)
        val startPos = startAtMs ?: ResumeRules.startPosition(library.progressOf(video.id)) ?: 0L
        c.setMediaItem(item, startPos)
        applyCaptions(c)
        c.prepare()
        d.serverSession?.let { reportServerStart(it, startPos) }
        _loading.value = false
        apply(effects)
      }
      is PlaybackDescriptor.YouTube -> {
        _controller.value?.run {
          stop()
          clearMediaItems()
        }
        ytPositionMs = d.startSeconds * 1000L
        ytDurationMs = video.durationMs ?: 0
        ytPlaylistIndex = 0
        _loading.value = false
        if (Effect.StopAndUnload in effects) {
          _youtube.value = null
          apply(effects)
        } else {
          // The embed starts itself when asked to (autoplay), once it's on screen and ready.
          _youtube.value = YouTubeRequest(d.youtubeVideoId, d.playlistId, d.startSeconds, Effect.Play in effects, settings.current.captions, ++nonce)
          apply(effects - Effect.Play)
        }
      }
    }
  }

  fun togglePlay() {
    val playing = machinePlaying()
    if (!playing) rewindIfEnded()
    apply(machine.on(if (playing) Event.UserPause else Event.UserPlay))
  }

  /** Play after the end starts from the top. */
  private fun rewindIfEnded() {
    when (_now.value?.descriptor) {
      is PlaybackDescriptor.Native -> _controller.value?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0) }
      is PlaybackDescriptor.YouTube -> if (ytDurationMs > 0 && ytPositionMs >= ytDurationMs - 1_000) youtubeHandle?.seekTo(0.0)
      null -> Unit
    }
  }

  /** "Resume video" after a drive. */
  fun resumeVideo() = apply(machine.on(Event.ResumeTapped))

  fun seekTo(ms: Long) {
    val np = _now.value ?: return
    when (np.descriptor) {
      is PlaybackDescriptor.Native -> _controller.value?.seekTo(ms.coerceAtLeast(0))
      is PlaybackDescriptor.YouTube -> youtubeHandle?.seekTo(ms.coerceAtLeast(0) / 1000.0)
      null -> Unit
    }
    _ui.update { it.copy(positionMs = ms.coerceAtLeast(0)) }
  }

  fun seekBy(deltaMs: Long) = seekTo(currentPositionMs() + deltaMs)

  /**
   * The player screen closed while the app stays open. YouTube may not play unseen, so it stops
   * (reopening the player and pressing play loads it again where it was); native sound carries on.
   */
  fun leftPlayerScreen() {
    val np = _now.value ?: return
    if (np.descriptor !is PlaybackDescriptor.YouTube) return
    if (machinePlaying()) apply(machine.on(Event.UserPause))
    savePosition()
    youtubeHandle?.stop()
    _youtube.value = null
    _ui.update { it.copy(playing = false, buffering = false) }
  }

  fun close() {
    apply(machine.on(Event.Dismiss))
    _now.value = null
    _problem.value = null
    _youtube.value = null
    _ui.value = PlayerUi()
  }

  fun setCaptions(on: Boolean) {
    settings.update { it.copy(captions = on) }
    _controller.value?.let { applyCaptions(it) }
    youtubeHandle?.captions(on)
  }

  /** Where a title picked from the car's media screen should start. */
  fun resumePositionFor(videoId: String): Long? = ResumeRules.startPosition(library.progressOf(videoId))

  fun currentPositionMs(): Long =
      when (_now.value?.descriptor) {
        is PlaybackDescriptor.Native -> _controller.value?.currentPosition ?: 0
        is PlaybackDescriptor.YouTube -> ytPositionMs
        null -> 0
      }

  // ---- Effects ----

  private fun apply(effects: List<Effect>) {
    _safety.value = machine.state
    val np = _now.value
    for (e in effects) {
      when (e) {
        Effect.Play,
        Effect.ContinueAsAudio ->
            when (val d = np?.descriptor) {
              is PlaybackDescriptor.Native -> _controller.value?.play()
              is PlaybackDescriptor.YouTube ->
                  // Unloaded by a drive: reload where it stopped, now that the picture may show.
                  if (_youtube.value == null) {
                    _youtube.value =
                        YouTubeRequest(d.youtubeVideoId, d.playlistId, (ytPositionMs / 1000).toInt(), true, settings.current.captions, ++nonce, ytPlaylistIndex)
                  } else {
                    youtubeHandle?.play()
                  }
              null -> Unit
            }
        Effect.Pause ->
            when (np?.descriptor) {
              is PlaybackDescriptor.Native -> _controller.value?.pause()
              is PlaybackDescriptor.YouTube -> youtubeHandle?.pause()
              null -> Unit
            }
        Effect.StopAndUnload ->
            when (np?.descriptor) {
              is PlaybackDescriptor.YouTube -> {
                youtubeHandle?.stop()
                _youtube.value = null
                _ui.update { it.copy(playing = false, buffering = false) }
              }
              is PlaybackDescriptor.Native -> {
                reportServerStopped()
                _controller.value?.run {
                  stop()
                  clearMediaItems()
                }
              }
              null -> Unit
            }
        Effect.SavePosition -> savePosition()
      }
    }
    _safety.value = machine.state
  }

  // ---- Player events ----

  private val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (_now.value?.descriptor is PlaybackDescriptor.Native) _ui.update { it.copy(playing = isPlaying) }
        }

        override fun onPlaybackStateChanged(state: Int) {
          if (_now.value?.descriptor !is PlaybackDescriptor.Native) return
          _ui.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
          if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
          if (_now.value?.descriptor !is PlaybackDescriptor.Native) return
          if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
          // Paused or played from outside (a call, the notification, the car's controls): keep the
          // rules in step. Playing while the picture is hidden goes through them again, so
          // something that may not play gets paused straight back.
          if (playWhenReady != machinePlaying()) apply(machine.on(if (playWhenReady) Event.UserPlay else Event.UserPause))
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
          if (videoSize.width > 0 && videoSize.height > 0) {
            _ui.update { it.copy(videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height) }
          }
        }

        override fun onTracksChanged(tracks: Tracks) {
          _ui.update { it.copy(captionsAvailable = tracks.groups.any { g -> g.type == C.TRACK_TYPE_TEXT }) }
        }

        override fun onPlayerError(error: PlaybackException) {
          Log.w(TAG, "Playback error ${error.errorCodeName}", error)
          val d = _now.value?.descriptor as? PlaybackDescriptor.Native
          _problem.value = problemForPlayback(error, d)
          apply(machine.on(Event.Failed))
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          val id = mediaItem?.mediaId ?: return
          if (_now.value?.video?.id == id) return
          // Started from the car's media screen or the notification: follow it.
          val video = library.find(id) ?: return
          val d = PlaybackDescriptor.Native(id, mediaItem.localConfiguration?.uri?.toString().orEmpty(), null, drivingAudioEligible = true)
          _now.value = NowPlaying(video, d, listOf(video), 0)
          _ui.value = PlayerUi(playing = _controller.value?.isPlaying == true, durationMs = video.durationMs ?: 0)
          library.markPlayed(video)
          if (shown) {
            apply(machine.on(Event.Load(d)))
          } else {
            val inCar = windows.values.any { it.screen != Screen.PHONE } || CarScreens.connection.value == CarConnection.CONNECTION_TYPE_PROJECTION
            machine.adoptAudio(d, if (inCar) HideReason.DRIVING else HideReason.BACKGROUND)
            _safety.value = machine.state
          }
        }
      }

  private fun machinePlaying(): Boolean =
      when (val s = machine.state) {
        is SafetyMachine.State.Watching -> s.playing
        is SafetyMachine.State.Hidden -> s.playback == HiddenPlayback.AUDIO_ONLY
        is SafetyMachine.State.ResumePrompt -> s.audioPlaying
        SafetyMachine.State.Idle -> false
      }

  private fun onEnded() {
    val np = _now.value ?: return
    val duration = _ui.value.durationMs.takeIf { it > 0 }
    if (duration != null) library.saveProgress(np.video.id, duration, duration)
    reportServerStopped()
    apply(machine.on(Event.Ended))
    if (settings.current.autoplayNext && np.hasNext) {
      launchStart(np.queue[np.index + 1], np.queue, np.index + 1, convert = false, startAtMs = null, automatic = true)
    }
  }

  // ---- YouTube embed bridge (main thread) ----

  fun attachYouTube(handle: YouTubeHandle) {
    youtubeHandle = handle
  }

  fun detachYouTube(handle: YouTubeHandle) {
    if (youtubeHandle === handle) youtubeHandle = null
  }

  fun isAttachedYouTube(handle: YouTubeHandle): Boolean = youtubeHandle === handle

  /** A layout change recreates the embed: load the current position and intent, not the first load. */
  fun requestForMount(request: YouTubeRequest): YouTubeRequest? {
    val current = _youtube.value ?: return null
    if (current.nonce != request.nonce || current.videoId != request.videoId || current.playlistId != request.playlistId) return null
    // Never load a player from a stale composition after the picture has become restricted.
    if (machine.state !is SafetyMachine.State.Watching || _now.value?.descriptor !is PlaybackDescriptor.YouTube) return null
    return current.copy(
        startSeconds = (ytPositionMs.coerceAtLeast(0) / 1000).toInt(),
        autoplay = machinePlaying(),
        captions = settings.current.captions,
        playlistIndex = ytPlaylistIndex,
    )
  }

  /** YouTube player states: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
  fun onYouTubeState(state: Int) {
    if (_now.value?.descriptor !is PlaybackDescriptor.YouTube) return
    _ui.update { it.copy(playing = state == 1, buffering = state == 3) }
    when (state) {
      0 -> {
        val d = _now.value?.descriptor as? PlaybackDescriptor.YouTube
        // A playlist moves on by itself; only its last video ends the session.
        if (d?.playlistId == null) onEnded()
      }
      1 -> if (!machinePlaying()) apply(machine.on(Event.UserPlay))
      2 -> if (machinePlaying()) apply(machine.on(Event.UserPause))
    }
  }

  fun onYouTubeProgress(currentSeconds: Double, durationSeconds: Double, playlistIndex: Int = 0) {
    val descriptor = _now.value?.descriptor as? PlaybackDescriptor.YouTube ?: return
    ytPositionMs = (currentSeconds * 1000).toLong()
    if (durationSeconds > 0) ytDurationMs = (durationSeconds * 1000).toLong()
    ytPlaylistIndex = if (descriptor.playlistId != null) playlistIndex.coerceAtLeast(0) else 0
    _ui.update { it.copy(positionMs = ytPositionMs, durationMs = ytDurationMs) }
  }

  fun onYouTubeError(code: Int) {
    Log.w(TAG, "YouTube player error $code")
    _problem.value = youtubeProblem(code)
    apply(machine.on(Event.Failed))
  }

  // ---- Positions and server reports ----

  private fun tick() {
    val np = _now.value ?: return
    if (np.descriptor is PlaybackDescriptor.Native) {
      _controller.value?.let { c ->
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        _ui.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = dur ?: it.durationMs) }
      }
    }
    if (!_ui.value.playing) return
    val t = SystemClock.elapsedRealtime()
    if (t - lastSave > 5_000) {
      lastSave = t
      savePosition()
    }
    if (t - lastReport > 30_000) {
      lastReport = t
      reportServerProgress()
    }
  }

  private fun savePosition() {
    val np = _now.value ?: return
    val d = np.descriptor ?: return
    if (d is PlaybackDescriptor.YouTube && d.playlistId != null) return
    val pos = currentPositionMs()
    val dur = _ui.value.durationMs.takeIf { it > 0 } ?: np.video.durationMs
    if (pos > 0) library.saveProgress(np.video.id, pos, dur)
  }

  private fun reportServerStart(s: ServerPlayback, positionMs: Long) {
    serverSession = s
    scope.launch { library.connection(s.sourceId)?.let { runCatching { jellyfin.reportStart(it, s.itemId, s.mediaSourceId, s.playSessionId, s.method, positionMs) } } }
  }

  private fun reportServerProgress() {
    val s = serverSession ?: return
    val pos = currentPositionMs()
    val paused = !_ui.value.playing
    scope.launch { library.connection(s.sourceId)?.let { runCatching { jellyfin.reportProgress(it, s.itemId, s.mediaSourceId, s.playSessionId, s.method, pos, paused) } } }
  }

  private fun reportServerStopped() {
    val s = serverSession ?: return
    serverSession = null
    val pos = currentPositionMs()
    scope.launch { library.connection(s.sourceId)?.let { runCatching { jellyfin.reportStopped(it, s.itemId, s.mediaSourceId, s.playSessionId, s.method, pos) } } }
  }

  private fun applyCaptions(c: Player) {
    val on = settings.current.captions
    c.trackSelectionParameters =
        c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .setPreferredTextLanguage(if (on) Locale.getDefault().language else null)
            .setSelectUndeterminedTextLanguage(on)
            .build()
  }

  // ---- Problems, in plain words ----

  private fun problemFor(e: Throwable, video: Video): Problem =
      when (e) {
        is JellyfinAuthException -> Problem("Sign in to the server again", e.message, canRetry = false, signIn = true)
        is HttpException -> Problem("The server refused this video", "It answered with error ${e.code}.")
        is IOException -> Problem(if (video.type == SourceType.JELLYFIN) "Can't reach your server" else "Can't reach this video", "Check the connection and try again.")
        else -> Problem("Can't play this video", e.message)
      }

  private fun problemForPlayback(e: PlaybackException, d: PlaybackDescriptor.Native?): Problem {
    val format =
        e.errorCode in
            setOf(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            )
    val network = e.errorCode in 2000..2999
    return when {
      format && d?.canAskServerToConvert == true ->
          Problem("This car can't play this format", "Your server can convert it as it plays. ${e.errorCodeName}", canConvert = true)
      format -> Problem("This format can't play here", e.errorCodeName)
      e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Problem("The live stream moved on", "Try again to jump back to live.")
      network -> Problem("The video stopped loading", "Check the connection or the link. ${e.errorCodeName}")
      else -> Problem("Can't play this video", e.errorCodeName)
    }
  }

  private fun youtubeProblem(code: Int): Problem =
      when (code) {
        2 -> Problem("This YouTube link doesn't work", "The video id isn't valid.", canRetry = false)
        5 -> Problem("The YouTube player hit a problem", "Try again.")
        100 -> Problem("This video is private or was removed", canRetry = false)
        101,
        150 -> Problem("Its owner allows it only on YouTube", "Open it in the YouTube app instead.", canRetry = false, openInYouTube = true)
        152,
        153 -> Problem("YouTube didn't accept the player", "Error $code. Updating Android System WebView usually fixes this.")
        else -> Problem("YouTube error $code", "Try again.")
      }

  private companion object {
    const val TAG = "PlayerHub"
  }
}
