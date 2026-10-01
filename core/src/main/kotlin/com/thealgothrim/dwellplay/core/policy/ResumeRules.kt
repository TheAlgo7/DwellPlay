package com.thealgothrim.dwellplay.core.policy

import kotlinx.serialization.Serializable

/** Where a title was left. */
@Serializable
data class Progress(val positionMs: Long, val durationMs: Long? = null, val updatedAtMs: Long = 0)

object ResumeRules {
  /** Less than this in, and it starts from the top. */
  const val MIN_RESUME_MS = 15_000L
  /** Within this of the end, it counts as watched. */
  const val END_MARGIN_MS = 45_000L

  /** Where to start [progress], or null for the beginning. */
  fun startPosition(progress: Progress?): Long? {
    if (progress == null || progress.positionMs < MIN_RESUME_MS) return null
    return if (isFinished(progress)) null else progress.positionMs
  }

  fun isFinished(progress: Progress): Boolean {
    val d = progress.durationMs ?: return false
    if (d <= 0) return false
    return progress.positionMs >= d - END_MARGIN_MS || progress.positionMs >= d * 0.95
  }

  /** 0..1 for a progress bar on a card, or null when there's nothing worth showing. */
  fun fraction(progress: Progress?): Float? {
    if (progress == null) return null
    val d = progress.durationMs ?: return null
    if (d <= 0 || progress.positionMs < MIN_RESUME_MS || isFinished(progress)) return null
    return (progress.positionMs.toFloat() / d).coerceIn(0f, 1f)
  }

  /** "32 min left", "1 h 5 min left". */
  fun remainingLabel(progress: Progress): String? {
    val d = progress.durationMs ?: return null
    val leftMin = ((d - progress.positionMs) / 60_000L).coerceAtLeast(1)
    return if (leftMin >= 60) "${leftMin / 60} h ${leftMin % 60} min left" else "$leftMin min left"
  }
}

/** "4:05", "1:02:09". */
fun formatClock(ms: Long): String {
  val total = (ms / 1000).coerceAtLeast(0)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
