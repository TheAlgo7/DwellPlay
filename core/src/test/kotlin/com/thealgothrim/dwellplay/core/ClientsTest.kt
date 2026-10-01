package com.thealgothrim.dwellplay.core

import com.thealgothrim.dwellplay.core.jellyfin.JellyfinAuthException
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinClient
import com.thealgothrim.dwellplay.core.jellyfin.JellyfinConnection
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.net.defaultHttpClient
import com.thealgothrim.dwellplay.core.youtube.YouTubeCatalog
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Jellyfin and YouTube clients against canned responses shaped like Jellyfin 12.1 and YouTube's APIs. */
class ClientsTest {
  private lateinit var server: MockWebServer
  private val http = defaultHttpClient()
  private val jellyfin = JellyfinClient(http, deviceId = "test-device", deviceName = "Test", appVersion = "0.1.0")

  @Before
  fun start() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun stop() = server.close()

  private fun base() = server.url("/jf").toString().trimEnd('/')

  private fun conn() = JellyfinConnection("jf1", base(), token = "tok", userId = "u1", serverId = "s1")

  @Test
  fun jellyfin_signIn_checksTheServerThenAuthenticates() = runTest {
    server.enqueue(MockResponse(body = """{"Id":"s1","ServerName":"Home","Version":"12.1.0"}"""))
    server.enqueue(MockResponse(body = """{"AccessToken":"tok","ServerId":"s1","User":{"Id":"u1","Name":"gaurav"}}"""))
    val login = jellyfin.signIn(base(), "gaurav", "pw")
    assertEquals("tok", login.token)
    assertEquals("Home", login.serverName)
    assertEquals("/jf/System/Info/Public", server.takeRequest().target)
    val auth = server.takeRequest()
    assertEquals("POST", auth.method)
    assertTrue(auth.headers["Authorization"]!!.contains("Client=\"DwellPlay\""))
    assertTrue(auth.body!!.utf8().contains("\"Username\":\"gaurav\""))
  }

  @Test
  fun jellyfin_wrongPassword_saysSo() = runTest {
    server.enqueue(MockResponse(body = """{"Id":"s1"}"""))
    server.enqueue(MockResponse(code = 401, body = ""))
    try {
      jellyfin.signIn(base(), "gaurav", "nope")
      fail()
    } catch (e: JellyfinAuthException) {
      assertEquals("Wrong user name or password.", e.message)
    }
  }

  @Test
  fun jellyfin_expiredToken_failsClosed() = runTest {
    server.enqueue(MockResponse(code = 401, body = ""))
    try {
      jellyfin.latest(conn())
      fail()
    } catch (e: JellyfinAuthException) {
      // Expected: nothing plays until the user signs in again.
    }
  }

  @Test
  fun jellyfin_directPlayWhenTheFileSuits() = runTest {
    server.enqueue(
        MockResponse(
            body =
                """{"PlaySessionId":"ps","MediaSources":[{"Id":"ms1","Container":"mov","SupportsDirectPlay":true,
                "SupportsDirectStream":true,"MediaStreams":[{"Type":"Video","Codec":"h264"},
                {"Type":"Subtitle","Language":"eng","DisplayTitle":"English","DeliveryMethod":"External","DeliveryUrl":"/Videos/i1/ms1/Subtitles/2/0/Stream.vtt"}]}]}"""
        )
    )
    val p = jellyfin.playback(conn(), "i1")
    assertEquals("DirectPlay", p.method)
    assertTrue(p.url.startsWith(base() + "/Videos/i1/stream?static=true&mediaSourceId=ms1"))
    assertTrue(p.url.contains("api_key=tok"))
    assertNull(p.mimeType)
    assertEquals(1, p.subtitles.size)
    assertTrue(p.subtitles[0].url.endsWith("Stream.vtt?api_key=tok"))
    assertEquals("text/vtt", p.subtitles[0].mimeType)
    val req = server.takeRequest()
    assertTrue(req.body!!.utf8().contains("\"EnableDirectPlay\":true"))
  }

  @Test
  fun jellyfin_askToConvert_usesTheServersHlsStream() = runTest {
    server.enqueue(
        MockResponse(
            body =
                """{"PlaySessionId":"ps","MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsDirectStream":false,
                "TranscodingUrl":"/videos/i1/master.m3u8?MediaSourceId=ms1&ApiKey=tok","MediaStreams":[]}]}"""
        )
    )
    val p = jellyfin.playback(conn(), "i1", convert = true)
    assertEquals("Transcode", p.method)
    assertEquals(JellyfinClient.MIME_HLS, p.mimeType)
    assertEquals(base() + "/videos/i1/master.m3u8?MediaSourceId=ms1&ApiKey=tok", p.url)
    assertTrue(server.takeRequest().body!!.utf8().contains("\"EnableDirectPlay\":false"))
  }

  @Test
  fun jellyfin_episodesGetShowAndNumber_andWideArt() = runTest {
    server.enqueue(
        MockResponse(
            body =
                """{"Items":[{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesName":"Pioneer One","ParentIndexNumber":1,
                "IndexNumber":2,"RunTimeTicks":27000000000,"ImageTags":{"Primary":"p"},"BackdropImageTags":["b"]}],"TotalRecordCount":1}"""
        )
    )
    val v = jellyfin.toVideo(conn(), jellyfin.latest(conn()).single())
    assertEquals("Pioneer One · S1 E2", v.subtitle)
    assertEquals(2_700_000L, v.durationMs)
    assertEquals(MediaRef.Jellyfin("jf1", "e1"), v.ref)
    assertTrue(v.artworkUrl!!.contains("/Items/e1/Images/Backdrop/0"))
  }

  @Test
  fun jellyfin_typedAddresses() {
    assertEquals(listOf("https://192.168.1.5:8096", "http://192.168.1.5:8096"), JellyfinClient.candidateBaseUrls("192.168.1.5:8096"))
    assertEquals(listOf("https://jf.example.com"), JellyfinClient.candidateBaseUrls("https://jf.example.com/web/index.html#!/home.html"))
    assertEquals(listOf("https://demo.jellyfin.org/stable"), JellyfinClient.candidateBaseUrls("https://demo.jellyfin.org/stable/"))
  }

  @Test
  fun youtube_sharedLinkGetsItsRealTitle_withoutAKey() = runTest {
    server.enqueue(
        MockResponse(body = """{"title":"Rick Astley - Never Gonna Give You Up","author_name":"Rick Astley","thumbnail_url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}""")
    )
    val catalog = YouTubeCatalog(http, apiKey = { null }, oembedBase = server.url("/oembed"), apiBase = server.url("/v3/"))
    val v = catalog.describe("dQw4w9WgXcQ", null, startSeconds = 30)
    assertEquals("yt:dQw4w9WgXcQ", v.id)
    assertEquals("Rick Astley - Never Gonna Give You Up", v.title)
    assertEquals("Rick Astley", v.subtitle)
    assertEquals(MediaRef.YouTube("dQw4w9WgXcQ", null, 30), v.ref)
    assertEquals(false, catalog.canSearch)
    assertTrue(catalog.search("anything").isEmpty())
  }

  @Test
  fun youtube_searchAsksOnlyForEmbeddableVideos() = runTest {
    server.enqueue(
        MockResponse(
            body =
                """{"items":[{"id":{"kind":"youtube#video","videoId":"yKNxeF4KMsY"},"snippet":{"title":"Coldplay - Yellow (Official Video)","channelTitle":"Coldplay"}}]}"""
        )
    )
    server.enqueue(MockResponse(body = """{"items":[{"id":"yKNxeF4KMsY","contentDetails":{"duration":"PT4M32S"}}]}"""))
    val catalog = YouTubeCatalog(http, apiKey = { "KEY" }, oembedBase = server.url("/oembed"), apiBase = server.url("/v3/"))
    val results = catalog.search("coldplay yellow")
    assertEquals(1, results.size)
    assertEquals(272_000L, results[0].durationMs)
    val search = server.takeRequest()
    assertEquals("true", search.url.queryParameter("videoEmbeddable"))
    assertEquals("coldplay yellow", search.url.queryParameter("q"))
  }
}
