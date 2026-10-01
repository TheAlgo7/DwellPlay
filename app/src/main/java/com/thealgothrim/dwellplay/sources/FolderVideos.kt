package com.thealgothrim.dwellplay.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.thealgothrim.dwellplay.core.model.MediaRef
import com.thealgothrim.dwellplay.core.model.Source
import com.thealgothrim.dwellplay.core.model.SourceType
import com.thealgothrim.dwellplay.core.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder the user picked with the system picker: a USB stick or SD card, or (on Android Automotive
 * OS) the car's own storage. Access is only to that folder, never the whole disk, and it survives
 * restarts because the grant is persisted.
 */
object FolderVideos {
  private const val MAX_FILES = 3000
  private const val MAX_DEPTH = 5

  fun keepAccess(context: Context, tree: Uri) {
    context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  fun releaseAccess(context: Context, tree: Uri) {
    runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
  }

  fun hasAccess(context: Context, tree: Uri): Boolean =
      context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

  /** A name for the picked folder ("Movies", "USB drive"). */
  fun displayName(context: Context, tree: Uri): String {
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return runCatching {
          context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
          }
        }
        .getOrNull() ?: "Folder"
  }

  suspend fun scan(context: Context, source: Source): List<Video> =
      withContext(Dispatchers.IO) {
        val tree = Uri.parse(source.folderUri ?: return@withContext emptyList())
        if (!hasAccess(context, tree)) return@withContext emptyList()
        val out = ArrayList<Video>()
        walk(context, tree, DocumentsContract.getTreeDocumentId(tree), source.name, 0, out, source.id)
        out.sortedByDescending { it.addedAtMs }
      }

  private fun walk(context: Context, tree: Uri, parentId: String, parentName: String, depth: Int, out: MutableList<Video>, sourceId: String) {
    if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val cols =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    val folders = ArrayList<Pair<String, String>>()
    runCatching {
      context.contentResolver.query(children, cols, null, null, null)?.use { c ->
        while (c.moveToNext() && out.size < MAX_FILES) {
          val id = c.getString(0)
          val name = c.getString(1) ?: continue
          val mime = c.getString(2).orEmpty()
          when {
            mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (!name.startsWith(".")) folders += id to name
            mime.startsWith("video/") -> {
              val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
              out +=
                  Video(
                      id = "folder:$sourceId:${id.hashCode().toUInt().toString(16)}",
                      sourceId = sourceId,
                      type = SourceType.FOLDER,
                      title = PhoneVideos.niceTitle(null, name),
                      subtitle = parentName,
                      ref = MediaRef.Content(uri.toString(), mime),
                      addedAtMs = if (c.isNull(3)) 0 else c.getLong(3),
                  )
            }
          }
        }
      }
    }
    for ((id, name) in folders) walk(context, tree, id, name, depth + 1, out, sourceId)
  }
}
