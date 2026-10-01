package com.thealgothrim.dwellplay.playback

import com.thealgothrim.dwellplay.core.jellyfin.JellyfinAuthException
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinClient
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.PlaybackDescriptor
import com.thealgothrim.dwellplay.core.model.ServerPlayback
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.sources.Library

/** Turns a library entry into what a player needs. Jellyfin items are negotiated with the server here. */
class Resolver(private val library: Library, private val jellyfin: JellyfinClient) {

  suspend fun resolve(video: Video, convert: Boolean = false): PlaybackDescriptor =
      when (val r = video.ref) {
        is MediaRef.Content -> PlaybackDescriptor.Native(video.id, r.uri, r.mimeType, drivingAudioEligible = true)
        is MediaRef.Stream ->
            PlaybackDescriptor.Native(video.id, r.url, MediaItems.mimeFor(r.kind), r.headers, drivingAudioEligible = true)
        is MediaRef.YouTube -> {
          // Playlists start at the top; a single video picks up where it was left.
          val resumeAt = if (r.playlistId == null) ResumeRules.startPosition(library.progressOf(video.id))?.let { (it / 1000).toInt() } else null
          PlaybackDescriptor.YouTube(video.id, r.videoId, r.playlistId, resumeAt ?: r.startSeconds)
        }
        is MediaRef.Jellyfin -> {
          val c = library.connection(r.sourceId) ?: throw JellyfinAuthException("Sign in to this server again (Sources).")
          val p = jellyfin.playback(c, r.itemId, convert)
          PlaybackDescriptor.Native(
              videoId = video.id,
              uri = p.url,
              mimeType = p.mimeType,
              subtitles = p.subtitles,
              drivingAudioEligible = true,
              canAskServerToConvert = p.method == "DirectPlay",
              serverSession = ServerPlayback(r.sourceId, r.itemId, p.mediaSourceId, p.playSessionId, p.method),
          )
        }
      }
}
