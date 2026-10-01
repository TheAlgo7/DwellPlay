package com.thealgothrim.dwellplay.core

import com.thealgothrim.dwellplay.core.links.LinkKind
import com.thealgothrim.dwellplay.core.links.Links
import com.thealgothrim.dwellplay.core.links.YouTubeLink
import com.thealgothrim.dwellplay.core.model.StreamKind
import com.thealgothrim.dwellplay.core.policy.Progress
import com.thealgothrim.dwellplay.core.policy.ResumeRules
import com.thealgothrim.dwellplay.core.policy.formatClock
import com.thealgothrim.dwellplay.core.search.LocalSearch
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.youtube.Iso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {

  @Test
  fun youtubeLinks_everyShapeTheAppsShare() {
    val id = "dQw4w9WgXcQ"
    val cases =
        mapOf(
            "https://www.youtube.com/watch?v=$id" to YouTubeLink(id, null, 0),
            "https://youtu.be/$id?si=abc123" to YouTubeLink(id, null, 0),
            "https://youtu.be/$id?t=73" to YouTubeLink(id, null, 73),
            "https://m.youtube.com/watch?v=$id&t=1m30s" to YouTubeLink(id, null, 90),
            "https://music.youtube.com/watch?v=$id&feature=share" to YouTubeLink(id, null, 0),
            "https://www.youtube.com/shorts/$id" to YouTubeLink(id, null, 0),
            "https://www.youtube.com/embed/$id?start=12" to YouTubeLink(id, null, 12),
            "https://www.youtube.com/live/$id" to YouTubeLink(id, null, 0),
            "https://www.youtube.com/watch?v=$id&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG" to
                YouTubeLink(id, "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", 0),
            "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG" to
                YouTubeLink(null, "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", 0),
        )
    for ((url, expected) in cases) assertEquals(url, expected, Links.parseYouTube(url))
  }

  @Test
  fun youtubeShareText_findsTheLinkAfterTheTitle() {
    val shared = "Coldplay - Yellow (Official Video)\nhttps://youtu.be/yKNxeF4KMsY?si=KX1"
    val kind = Links.classify(shared) as LinkKind.YouTube
    assertEquals("yKNxeF4KMsY", kind.link.videoId)
  }

  @Test
  fun youtubeMixes_areNotPlaylists() {
    val link = Links.parseYouTube("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1")!!
    assertNull(link.playlistId)
    assertEquals("dQw4w9WgXcQ", link.videoId)
  }

  @Test
  fun youtubeWithoutAVideo_isRejected() {
    assertNull(Links.parseYouTube("https://www.youtube.com/@RickAstleyYT"))
    assertTrue(Links.classify("https://www.youtube.com/feed/subscriptions") is LinkKind.Unsupported)
  }

  @Test
  fun streams_areRecognisedByTheirPath() {
    assertEquals(StreamKind.HLS, Links.streamKind("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
    assertEquals(StreamKind.DASH, Links.streamKind("https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"))
    assertEquals(StreamKind.PROGRESSIVE, Links.streamKind("http://192.168.1.5/videos/clip.MKV"))
    assertEquals(StreamKind.AUTO, Links.streamKind("https://example.com/watch/123"))
  }

  @Test
  fun subscriptionServices_areNeverPlayedAsStreams() {
    val kind = Links.classify("https://www.netflix.com/watch/81234567")
    assertTrue(kind is LinkKind.Unsupported)
    assertTrue((kind as LinkKind.Unsupported).reason.contains("Netflix"))
    assertTrue(Links.classify("https://www.primevideo.com/detail/0ABC") is LinkKind.Unsupported)
  }

  @Test
  fun defaultNames_skipGenericManifestNames() {
    assertEquals("test-streams.mux.dev", Links.defaultName("https://test-streams.mux.dev/x/master.m3u8"))
    assertEquals("Concert Night", Links.defaultName("http://10.0.0.2/media/Concert_Night.mp4"))
  }

  @Test
  fun startTimes() {
    assertEquals(3723, Links.parseStart("1h2m3s"))
    assertEquals(45, Links.parseStart("45s"))
    assertEquals(0, Links.parseStart("nonsense"))
  }

  @Test
  fun resumeRules() {
    assertNull(ResumeRules.startPosition(Progress(5_000, 600_000)))
    assertEquals(120_000L, ResumeRules.startPosition(Progress(120_000, 600_000)))
    assertNull("nearly finished starts over", ResumeRules.startPosition(Progress(590_000, 600_000)))
    assertEquals("8 min left", ResumeRules.remainingLabel(Progress(120_000, 600_000)))
    assertEquals("1:02:09", formatClock(3_729_000))
    assertEquals("4:05", formatClock(245_000))
  }

  @Test
  fun isoDurations() {
    assertEquals(253_000L, Iso8601.durationMs("PT4M13S"))
    assertEquals(3_600_000L, Iso8601.durationMs("PT1H"))
    assertEquals(0L, Iso8601.durationMs("P0D"))
  }

  @Test
  fun localSearch_ignoresCaseAccentsAndPunctuation() {
    fun v(id: String, title: String, sub: String? = null) =
        Video(id, "phone", SourceType.PHONE, title, sub, ref = MediaRef.Content("content://$id"))
    val all = listOf(v("1", "Beyoncé - Halo (Live)"), v("2", "Midnight Sessions", "Road trip"), v("3", "Halo 3 trailer"))
    assertEquals(listOf("1", "3"), LocalSearch.search(all, "halo").map { it.id }.sorted())
    assertEquals(listOf("1"), LocalSearch.search(all, "beyonce halo").map { it.id })
    assertEquals(listOf("2"), LocalSearch.search(all, "road").map { it.id })
  }
}
