package com.thealgothrim.dwellplay.core.policy

import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor

/** Which screen the app's window is on. */
enum class Screen {
  PHONE,
  /** The car's screen through Android Auto (the app still runs on the phone). */
  ANDROID_AUTO,
  /** A car running Android Automotive OS (Google built-in): the app runs in the car. */
  AUTOMOTIVE,
}

/** Why the video can't be seen. */
enum class HideReason {
  /** The car is moving: driving restrictions are on, or Android Auto took the screen away. */
  DRIVING,
  /** The app left the screen for another reason (home button, another app, screen off). */
  BACKGROUND,
}

/** What happens to playback when the video can no longer be seen. */
enum class HiddenPlayback {
  /** YouTube: stopped completely. Its terms forbid audio-only or background play. */
  STOPPED,
  /** Paused where it was. */
  PAUSED,
  /** Own media on a car (or phone) that allows it: the sound carries on, the picture does not. */
  AUDIO_ONLY,
}

/**
 * The one place that decides what playback may do when the picture has to go away. The platform's
 * restriction signal is the input; there is deliberately no "gear == PARK" shortcut anywhere.
 */
object SafetyPolicy {

  /**
   * The report's `transitionForDriving`, extended to backgrounding: YouTube always stops; native
   * media carries on as audio only when the source is eligible, the vehicle allows background audio
   * while driving, and the user switched it on.
   */
  fun whenHidden(
      descriptor: PlaybackDescriptor,
      reason: HideReason,
      vehicleAllowsBackgroundAudio: Boolean,
      userAllowsBackgroundAudio: Boolean,
  ): HiddenPlayback =
      when (descriptor) {
        is PlaybackDescriptor.YouTube -> HiddenPlayback.STOPPED
        is PlaybackDescriptor.Native -> {
          val vehicleOk = reason != HideReason.DRIVING || vehicleAllowsBackgroundAudio
          if (descriptor.drivingAudioEligible && vehicleOk && userAllowsBackgroundAudio) HiddenPlayback.AUDIO_ONLY
          else HiddenPlayback.PAUSED
        }
      }

  /** Video may be drawn only while nothing hides it. */
  fun videoMayShow(hidden: HideReason?): Boolean = hidden == null

  /**
   * After the car stops, moving pictures never start by themselves: the user taps "Resume video".
   * Coming back from the home screen on a phone is the user's own choice, so it needs no prompt.
   */
  fun needsResumeTap(after: HideReason): Boolean = after == HideReason.DRIVING
}
