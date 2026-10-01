package com.thealgothrim.dwellplay.core

import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import com.thealgothrim.dwellplay.core.policy.HiddenPlayback
import com.thealgothrim.dwellplay.core.policy.HideReason
import com.thealgothrim.dwellplay.core.policy.SafetyMachine
import com.thealgothrim.dwellplay.core.policy.SafetyMachine.Effect
import com.thealgothrim.dwellplay.core.policy.SafetyMachine.Event
import com.thealgothrim.dwellplay.core.policy.SafetyMachine.State
import com.thealgothrim.dwellplay.core.policy.SafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car-safety contract. Each test is one line of the release checklist in docs/architecture.md:
 * no path from driving to moving video, YouTube never becomes background audio, and pictures never
 * restart by themselves after a drive.
 */
class SafetyTest {

  private val youtube = PlaybackDescriptor.YouTube("yt:abc", "abcdefghijk", null, 0)
  private val ownFile = PlaybackDescriptor.Native("phone:1", "content://x", null, drivingAudioEligible = true)
  private val notEligible = PlaybackDescriptor.Native("stream:1", "https://x/y.m3u8", null, drivingAudioEligible = false)

  private fun machine(vehicle: Boolean = true, user: Boolean = true) = SafetyMachine({ vehicle }, { user })

  /** No effect in [effects] may put a moving picture on screen. */
  private fun assertNoPicture(effects: List<Effect>) = assertFalse("picture started: $effects", Effect.Play in effects)

  @Test
  fun youtube_neverAllowsDrivingAudio() {
    for (vehicle in listOf(true, false)) for (user in listOf(true, false)) for (reason in HideReason.entries) {
      assertEquals(HiddenPlayback.STOPPED, SafetyPolicy.whenHidden(youtube, reason, vehicle, user))
    }
  }

  @Test
  fun ownMedia_continuesAsAudio_onlyWhenCarAndUserAllowIt() {
    assertEquals(HiddenPlayback.AUDIO_ONLY, SafetyPolicy.whenHidden(ownFile, HideReason.DRIVING, true, true))
    assertEquals(HiddenPlayback.PAUSED, SafetyPolicy.whenHidden(ownFile, HideReason.DRIVING, false, true))
    assertEquals(HiddenPlayback.PAUSED, SafetyPolicy.whenHidden(ownFile, HideReason.DRIVING, true, false))
    assertEquals(HiddenPlayback.PAUSED, SafetyPolicy.whenHidden(notEligible, HideReason.DRIVING, true, true))
    // Leaving the app on a phone isn't driving: the car's capability doesn't matter.
    assertEquals(HiddenPlayback.AUDIO_ONLY, SafetyPolicy.whenHidden(ownFile, HideReason.BACKGROUND, false, true))
  }

  @Test
  fun parked_videoBegins() {
    val m = machine()
    assertEquals(listOf(Effect.Play), m.on(Event.Load(ownFile)))
    assertEquals(State.Watching(ownFile, playing = true), m.state)
  }

  @Test
  fun drivingRestriction_stopsYouTubeCompletely() {
    val m = machine()
    m.on(Event.Load(youtube))
    val effects = m.on(Event.Hide(HideReason.DRIVING))
    assertTrue(Effect.StopAndUnload in effects)
    assertFalse(Effect.ContinueAsAudio in effects)
    assertEquals(HiddenPlayback.STOPPED, (m.state as State.Hidden).playback)
  }

  @Test
  fun drivingRestriction_ownMediaCarriesOnAsAudio() {
    val m = machine()
    m.on(Event.Load(ownFile))
    assertEquals(listOf(Effect.ContinueAsAudio), m.on(Event.Hide(HideReason.DRIVING)))
  }

  @Test
  fun drivingRestriction_pausesWhenCarHasNoBackgroundAudio() {
    val m = machine(vehicle = false)
    m.on(Event.Load(ownFile))
    val effects = m.on(Event.Hide(HideReason.DRIVING))
    assertEquals(listOf(Effect.Pause, Effect.SavePosition), effects)
  }

  @Test
  fun driving_pressingPlayNeverBringsThePictureBack() {
    val m = machine()
    m.on(Event.Load(youtube))
    m.on(Event.Hide(HideReason.DRIVING))
    assertNoPicture(m.on(Event.UserPlay))
    assertNoPicture(m.on(Event.Load(ownFile)))
    assertNoPicture(m.on(Event.UserPlay))
    assertTrue(m.state is State.Hidden)
  }

  @Test
  fun driving_choosingYouTubeFromTheCarStopsIt() {
    val m = machine()
    m.on(Event.Load(ownFile))
    m.on(Event.Hide(HideReason.DRIVING))
    val effects = m.on(Event.Load(youtube))
    assertEquals(listOf(Effect.StopAndUnload), effects)
  }

  @Test
  fun park_afterDrive_waitsForResumeTap() {
    val m = machine()
    m.on(Event.Load(ownFile))
    m.on(Event.Hide(HideReason.DRIVING))
    assertNoPicture(m.on(Event.Show))
    assertEquals(State.ResumePrompt(ownFile, audioPlaying = true), m.state)
    assertEquals(listOf(Effect.Play), m.on(Event.ResumeTapped))
    assertEquals(State.Watching(ownFile, playing = true), m.state)
  }

  @Test
  fun park_afterDrive_youtubeWaitsStopped() {
    val m = machine()
    m.on(Event.Load(youtube))
    m.on(Event.Hide(HideReason.DRIVING))
    assertNoPicture(m.on(Event.Show))
    assertEquals(State.ResumePrompt(youtube, audioPlaying = false), m.state)
  }

  @Test
  fun background_thenBack_needsNoPrompt() {
    val m = machine()
    m.on(Event.Load(ownFile))
    m.on(Event.Hide(HideReason.BACKGROUND))
    m.on(Event.Show)
    assertEquals(State.Watching(ownFile, playing = true), m.state)
  }

  @Test
  fun background_thenCarMoves_becomesADrivingHide() {
    val m = machine(vehicle = false)
    m.on(Event.Load(ownFile))
    m.on(Event.Hide(HideReason.BACKGROUND)) // audio carries on in the background
    val effects = m.on(Event.Hide(HideReason.DRIVING))
    assertEquals(listOf(Effect.Pause, Effect.SavePosition), effects)
    m.on(Event.Show)
    assertTrue("a drive always ends in the prompt", m.state is State.ResumePrompt)
  }

  @Test
  fun pausedVideo_staysPausedWhenHidden() {
    val m = machine()
    m.on(Event.Load(ownFile))
    m.on(Event.UserPause)
    val effects = m.on(Event.Hide(HideReason.DRIVING))
    assertFalse(Effect.ContinueAsAudio in effects)
    assertEquals(HiddenPlayback.PAUSED, (m.state as State.Hidden).playback)
  }

  @Test
  fun soundStartedFromTheCarsMediaScreen_showsOnlyAfterResume() {
    val m = machine()
    m.adoptAudio(ownFile, HideReason.DRIVING)
    assertNoPicture(m.on(Event.Show))
    assertEquals(State.ResumePrompt(ownFile, audioPlaying = true), m.state)
  }

  @Test
  fun dismiss_unloadsEverything() {
    val m = machine()
    m.on(Event.Load(youtube))
    assertEquals(listOf(Effect.SavePosition, Effect.StopAndUnload), m.on(Event.Dismiss))
    assertEquals(State.Idle, m.state)
  }
}
