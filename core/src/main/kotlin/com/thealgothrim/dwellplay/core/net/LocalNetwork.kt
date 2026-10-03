package com.thealgothrim.dwellplay.core.net

import java.net.URI

/**
 * Plain http reaches only the user's own network: a Jellyfin box at 192.168.1.20:8096, a NAS, a
 * Tailscale address. Anything on the internet must use https, so sign-in tokens and what someone
 * watches never cross the internet unencrypted. Android can't list private address ranges in a
 * network security config, so the rule lives here, where every address enters the app.
 */
object LocalNetwork {
  private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

  fun isLocalHost(host: String): Boolean {
    val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    if (h.isEmpty()) return false
    if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
    ipv4(h)?.let { (a, b) ->
      return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
          (a == 169 && b == 254) || (a == 100 && b in 64..127) // 100.64/10: carrier NAT and Tailscale
    }
    if (':' in h) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || Regex("^fe[89ab]").containsMatchIn(h)
    // A bare name with no dots ("nas", "jellyfin") only resolves on the local network.
    return '.' !in h
  }

  /** https anywhere; http only to an address on the local network. */
  fun allowed(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
      "https" -> true
      "http" -> uri.host?.let(::isLocalHost) == true
      else -> false
    }
  }

  private fun ipv4(h: String): Pair<Int, Int>? {
    val parts = h.split('.')
    if (parts.size != 4) return null
    val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && it in 0..255 } ?: return null }
    return n[0] to n[1]
  }
}
