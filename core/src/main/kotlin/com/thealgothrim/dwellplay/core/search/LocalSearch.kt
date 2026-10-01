package com.thealgothrim.dwellplay.core.search

import com.thealgothrim.dwellplay.core.model.Video
import java.text.Normalizer

/** Title search over what's already on the device, forgiving about case, accents and punctuation. */
object LocalSearch {

  fun search(videos: List<Video>, query: String, limit: Int = 60): List<Video> {
    val q = tokens(query)
    if (q.isEmpty()) return emptyList()
    return videos
        .asSequence()
        .mapNotNull { v -> score(v, q)?.let { v to it } }
        .sortedByDescending { it.second }
        .map { it.first }
        .distinctBy { it.id }
        .take(limit)
        .toList()
  }

  private fun score(v: Video, q: List<String>): Int? {
    val title = normalize(v.title)
    val sub = normalize(v.subtitle.orEmpty())
    var score = 0
    for (t in q) {
      score +=
          when {
            title.split(' ').any { it.startsWith(t) } -> 3
            title.contains(t) -> 2
            sub.contains(t) -> 1
            else -> return null
          }
    }
    if (title.startsWith(q.joinToString(" "))) score += 5
    return score
  }

  fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotBlank() }

  fun normalize(s: String): String =
      Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
          .replace(Regex("\\p{Mn}+"), "")
          .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
          .trim()
}
