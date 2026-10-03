package com.thealgothrim.dwellplay.core.policy

import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor

/**
 * Playback's safety state, as a pure reducer so every transition in docs/architecture.md is a unit
 * test. The app feeds it events (the user, the player, the car) and carries out the [Effect]s.
 *
 * ```
 * Idle -> Watching (play) -> Hidden (car moves / app leaves) -> ResumePrompt (car parks) -> Watching (tap)
 * ```
 */
class SafetyMachine(
    private val vehicleAllowsBackgroundAudio: () -> Boolean,
    private val userAllowsBackgroundAudio: () -> Boolean,
) {

  sealed interface State {
    /** Nothing loaded. */
    data object Idle : State

    /** The video is on screen, playing or paused by the user. */
    data class Watching(val descriptor: PlaybackDescriptor, val playing: Boolean) : State

    /** The picture is gone. [playback] says what the sound is doing. */
    data class Hidden(
        val descriptor: PlaybackDescriptor,
        val reason: HideReason,
        val playback: HiddenPlayback,
        val wasPlaying: Boolean,
    ) : State

    /** Back on screen after driving. Nothing moves until the user taps Resume (audio may still play). */
    data class ResumePrompt(val descriptor: PlaybackDescriptor, val audioPlaying: Boolean) : State
  }

  sealed interface Event {
    /** Automatic queue advances must preserve the post-drive Resume gate. */
    data class Load(val descriptor: PlaybackDescriptor, val automatic: Boolean = false) : Event
    data object UserPlay : Event
    data object UserPause : Event
    data class Hide(val reason: HideReason) : Event
    data object Show : Event
    data object ResumeTapped : Event
    data object Dismiss : Event
    data object Ended : Event
    data object Failed : Event
  }

  sealed interface Effect {
    /** Start or continue playback, picture included. */
    data object Play : Effect
    data object Pause : Effect
    /** YouTube: stop and unload the embedded player. */
    data object StopAndUnload : Effect
    /** Keep the sound going with no picture. */
    data object ContinueAsAudio : Effect
    data object SavePosition : Effect
  }

  var state: State = State.Idle
    private set

  /** Applies [event] and returns what the app must do, in order. */
  fun on(event: Event): List<Effect> {
    val (next, effects) = reduce(state, event)
    state = next
    return effects
  }

  /**
   * Sound that started outside the app's screens (the car's media screen while driving, the
   * notification) with no picture showing. When a screen shows it later, it's a driving hide, so the
   * picture waits for Resume.
   */
  fun adoptAudio(descriptor: PlaybackDescriptor, reason: HideReason) {
    state = State.Hidden(descriptor, reason, HiddenPlayback.AUDIO_ONLY, wasPlaying = true)
  }

  private fun reduce(s: State, e: Event): Pair<State, List<Effect>> =
      when (e) {
        is Event.Load ->
            when (s) {
              // A new title chosen while hidden (from the car's media screen while driving) plays as
              // sound only, and only if the rules allow it for that title.
              is State.Hidden -> hiddenStart(e.descriptor, s.reason)
              is State.ResumePrompt ->
                  if (e.automatic) startBehindResumePrompt(e.descriptor)
                  else State.Watching(e.descriptor, playing = true) to listOf(Effect.Play)
              else -> State.Watching(e.descriptor, playing = true) to listOf(Effect.Play)
            }
        Event.UserPlay ->
            when (s) {
              is State.Watching -> s.copy(playing = true) to listOf(Effect.Play)
              // A notification or the car's media controls can resume sound without restoring the
              // picture. Resume or choosing a title in the app remains a deliberate action.
              is State.ResumePrompt -> startBehindResumePrompt(s.descriptor)
              // Play from the car's media controls while the picture is hidden.
              is State.Hidden -> hiddenStart(s.descriptor, s.reason)
              State.Idle -> s to emptyList()
            }
        Event.UserPause ->
            when (s) {
              is State.Watching -> s.copy(playing = false) to listOf(Effect.Pause, Effect.SavePosition)
              is State.Hidden ->
                  s.copy(playback = if (s.playback == HiddenPlayback.AUDIO_ONLY) HiddenPlayback.PAUSED else s.playback, wasPlaying = false) to
                      listOf(Effect.Pause, Effect.SavePosition)
              is State.ResumePrompt -> s.copy(audioPlaying = false) to listOf(Effect.Pause, Effect.SavePosition)
              State.Idle -> s to emptyList()
            }
        is Event.Hide ->
            when (s) {
              is State.Watching -> hide(s.descriptor, e.reason, s.playing)
              is State.ResumePrompt -> hide(s.descriptor, e.reason, s.audioPlaying)
              // Driving outranks backgrounding: once the car moves, it's a driving hide.
              is State.Hidden ->
                  if (e.reason == HideReason.DRIVING && s.reason != HideReason.DRIVING) hide(s.descriptor, e.reason, s.wasPlaying)
                  else s to emptyList()
              State.Idle -> s to emptyList()
            }
        Event.Show ->
            when (s) {
              is State.Hidden ->
                  if (SafetyPolicy.needsResumeTap(s.reason)) {
                    State.ResumePrompt(s.descriptor, audioPlaying = s.playback == HiddenPlayback.AUDIO_ONLY) to emptyList()
                  } else {
                    // Back from the home screen: the sound that carried on keeps its picture.
                    State.Watching(s.descriptor, playing = s.playback == HiddenPlayback.AUDIO_ONLY) to emptyList()
                  }
              else -> s to emptyList()
            }
        Event.ResumeTapped ->
            when (s) {
              is State.ResumePrompt -> State.Watching(s.descriptor, playing = true) to listOf(Effect.Play)
              else -> s to emptyList()
            }
        Event.Dismiss ->
            when (s) {
              State.Idle -> s to emptyList()
              else -> State.Idle to listOf(Effect.SavePosition, Effect.StopAndUnload)
            }
        Event.Ended ->
            when (s) {
              is State.Watching -> s.copy(playing = false) to listOf(Effect.SavePosition)
              is State.Hidden -> s.copy(playback = HiddenPlayback.PAUSED, wasPlaying = false) to listOf(Effect.SavePosition)
              is State.ResumePrompt -> s.copy(audioPlaying = false) to listOf(Effect.SavePosition)
              State.Idle -> s to emptyList()
            }
        Event.Failed ->
            when (s) {
              is State.Watching -> s.copy(playing = false) to listOf(Effect.SavePosition)
              is State.Hidden -> s.copy(playback = HiddenPlayback.PAUSED, wasPlaying = false) to emptyList()
              is State.ResumePrompt -> s.copy(audioPlaying = false) to emptyList()
              State.Idle -> s to emptyList()
            }
      }

  private fun hide(descriptor: PlaybackDescriptor, reason: HideReason, playing: Boolean): Pair<State, List<Effect>> {
    val playback =
        SafetyPolicy.whenHidden(descriptor, reason, vehicleAllowsBackgroundAudio(), userAllowsBackgroundAudio())
    val effects =
        when (playback) {
          HiddenPlayback.STOPPED -> listOf(Effect.SavePosition, Effect.StopAndUnload)
          HiddenPlayback.PAUSED -> listOf(Effect.Pause, Effect.SavePosition)
          // Nothing was playing: there's no sound to carry on, so it stays paused.
          HiddenPlayback.AUDIO_ONLY -> if (playing) listOf(Effect.ContinueAsAudio) else listOf(Effect.SavePosition)
        }
    val actual = if (playback == HiddenPlayback.AUDIO_ONLY && !playing) HiddenPlayback.PAUSED else playback
    return State.Hidden(descriptor, reason, actual, wasPlaying = playing) to effects
  }

  /** Starting something while the picture is hidden: sound only, and only when allowed. */
  private fun hiddenStart(descriptor: PlaybackDescriptor, reason: HideReason): Pair<State.Hidden, List<Effect>> {
    val playback =
        SafetyPolicy.whenHidden(descriptor, reason, vehicleAllowsBackgroundAudio(), userAllowsBackgroundAudio())
    return if (playback == HiddenPlayback.AUDIO_ONLY) {
      State.Hidden(descriptor, reason, HiddenPlayback.AUDIO_ONLY, wasPlaying = true) to listOf(Effect.ContinueAsAudio)
    } else {
      State.Hidden(descriptor, reason, playback, wasPlaying = false) to
          listOf(if (playback == HiddenPlayback.STOPPED) Effect.StopAndUnload else Effect.Pause)
    }
  }

  /** Automatic queue advances and media transport controls leave the post-drive picture gated. */
  private fun startBehindResumePrompt(descriptor: PlaybackDescriptor): Pair<State, List<Effect>> {
    val (hidden, effects) = hiddenStart(descriptor, HideReason.DRIVING)
    return State.ResumePrompt(descriptor, audioPlaying = hidden.playback == HiddenPlayback.AUDIO_ONLY) to effects
  }
}
