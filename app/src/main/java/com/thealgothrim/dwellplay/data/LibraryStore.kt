package com.thealgothrim.dwellplay.data

import android.util.Log
import com.thealgothrim.dwellplay.core.model.Source
import com.thealgothrim.dwellplay.core.model.Video
import com.thealgothrim.dwellplay.core.net.DwellJson
import com.thealgothrim.dwellplay.core.policy.Progress
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Everything DwellPlay remembers. Phone and folder videos aren't stored: they're read fresh from the device. */
@Serializable
data class LibraryData(
    /** Added sources: picked folders and Jellyfin servers. Phone, YouTube and links are built in. */
    val sources: List<Source> = emptyList(),
    /** YouTube videos, playlists and links the user added, newest first. */
    val saved: List<Video> = emptyList(),
    /** Where each title was left, by video id. */
    val progress: Map<String, Progress> = emptyMap(),
    /** Recently played, newest first, as snapshots so they show even when their source is offline. */
    val recent: List<Video> = emptyList(),
)

/** The library as one JSON file in app storage, written atomically shortly after each change. */
class LibraryStore(private val file: File, private val scope: CoroutineScope) {
  private val state = MutableStateFlow(load())
  val data: StateFlow<LibraryData> = state.asStateFlow()
  private var pending: Job? = null

  val current: LibraryData
    get() = state.value

  fun update(change: (LibraryData) -> LibraryData) {
    state.update(change)
    pending?.cancel()
    pending =
        scope.launch(Dispatchers.IO) {
          delay(400)
          write(state.value)
        }
  }

  /** Writes now (the app is going away). */
  fun flush() {
    pending?.cancel()
    write(state.value)
  }

  private fun load(): LibraryData =
      runCatching { if (file.exists()) DwellJson.decodeFromString(LibraryData.serializer(), file.readText()) else LibraryData() }
          .getOrElse {
            Log.w(TAG, "Library file unreadable, starting empty (kept as .broken)", it)
            runCatching { file.copyTo(File(file.path + ".broken"), overwrite = true) }
            LibraryData()
          }

  @Synchronized
  private fun write(data: LibraryData) {
    val tmp = File(file.path + ".tmp")
    tmp.writeText(DwellJson.encodeToString(LibraryData.serializer(), data))
    if (!tmp.renameTo(file)) {
      file.delete()
      tmp.renameTo(file)
    }
  }

  private companion object {
    const val TAG = "LibraryStore"
  }
}
