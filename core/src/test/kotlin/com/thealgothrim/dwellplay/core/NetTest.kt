package com.thealgothrim.dwellplay.core

import com.thealgothrim.dwellplay.core.jellyfin.JellyfinClient
import com.thealgothrim.dwellplay.core.links.LinkKind
import com.thealgothrim.dwellplay.core.links.Links
import com.thealgothrim.dwellplay.core.net.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTest {
  @Test
  fun localAddresses_mayUsePlainHttp() {
    for (host in listOf("192.168.1.20", "10.0.0.5", "172.16.4.1", "172.31.255.1", "127.0.0.1", "169.254.3.3", "100.101.102.103", "localhost", "nas", "jellyfin.local", "media.lan", "box.home.arpa", "[::1]", "fd12:3456::1", "fe80::1")) {
      assertTrue(host, LocalNetwork.isLocalHost(host))
    }
  }

  @Test
  fun internetAddresses_needHttps() {
    for (host in listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "100.128.0.1", "demo.jellyfin.org", "example.com", "2001:4860::8888", "999.1.1.1")) {
      assertFalse(host, LocalNetwork.isLocalHost(host))
    }
    assertTrue(LocalNetwork.allowed("https://example.com/movie.m3u8"))
    assertFalse(LocalNetwork.allowed("http://example.com/movie.m3u8"))
    assertTrue(LocalNetwork.allowed("http://192.168.1.20:8096/stream.mp4"))
    assertFalse(LocalNetwork.allowed("ftp://192.168.1.20/movie.mp4"))
  }

  @Test
  fun jellyfin_publicHttpIsUpgradedToHttps() {
    assertEquals(listOf("https://demo.jellyfin.org/stable"), JellyfinClient.candidateBaseUrls("http://demo.jellyfin.org/stable"))
    assertEquals(listOf("https://demo.jellyfin.org"), JellyfinClient.candidateBaseUrls("demo.jellyfin.org"))
    assertEquals(listOf("https://192.168.1.20:8096", "http://192.168.1.20:8096"), JellyfinClient.candidateBaseUrls("192.168.1.20:8096"))
    assertEquals(listOf("http://192.168.1.20:8096"), JellyfinClient.candidateBaseUrls("http://192.168.1.20:8096"))
  }

  @Test
  fun streams_publicHttpIsRefused() {
    assertTrue(Links.classify("http://example.com/live/index.m3u8") is LinkKind.Unsupported)
    assertTrue(Links.classify("https://example.com/live/index.m3u8") is LinkKind.Stream)
    assertTrue(Links.classify("http://192.168.1.20:8080/movie.mp4") is LinkKind.Stream)
  }
}
