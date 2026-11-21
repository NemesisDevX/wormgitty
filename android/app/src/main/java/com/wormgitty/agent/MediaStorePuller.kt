package com.wormgitty.agent

import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * MediaStore extraction with a permanent ledger.
 *
 * Storage layout under files/operator/:
 *   transfered.json   — ledger (ids already pulled, batch count, last marker)
 *   evidence.txt      — human/CI evidence: ids + destination path per batch
 *   pulled/           — copied image files (stable names by id)
 */
class MediaStorePuller(private val context: Context, private val keySource: KeySource) {

    private val operatorDir: File
        get() = File(context.filesDir, "operator").apply { if (!exists()) mkdirs() }

    private val ledgerFile: File get() = File(operatorDir, "transfered.json")
    private val evidenceFile: File get() = File(operatorDir, "evidence.txt")
    private val pulledDir: File
        get() = File(operatorDir, "pulled").apply { if (!exists()) mkdirs() }

    fun loadLedger(): PhotoLedger =
        ledgerFile.takeIf { it.exists() }
            ?.let { LedgerCodec.decode(it.readText()) }
            ?: PhotoLedger()

    private fun saveLedger(ledger: PhotoLedger) {
        ledgerFile.writeText(LedgerCodec.encode(ledger))
    }

    fun evidenceText(): String? = evidenceFile.takeIf { it.exists() }?.readText()

    /** All image rows from MediaStore, DATE_ADDED ascending. */
    fun queryCandidates(): List<PhotoExtractorCore.Candidate> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val out = mutableListOf<PhotoExtractorCore.Candidate>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val mime = c.getString(mimeCol) ?: ""
                if (!mime.startsWith("image/")) continue
                out += PhotoExtractorCore.Candidate(
                    id = c.getLong(idCol),
                    dateAdded = c.getLong(dateCol),
                    displayName = c.getString(nameCol) ?: "img_${c.getLong(idCol)}"
                )
            }
        }
        return out
    }

    data class BatchResult(
        val existing: Int,
        val pulled: Int,
        val remaining: Int,
        val paths: List<String>
    )

    /**
     * Extract up to [n] oldest never-transferred images. Copies bytes into
     * app-private storage, appends the ledger (dedup guarantee), and writes an
     * evidence entry with ids + destination paths.
     */
    fun pullOldest(n: Int): BatchResult {
        val candidates = queryCandidates()
        val ledger = loadLedger()
        val known = ledger.transferredIds.toSet()
        val existing = candidates.size
        val remainingBefore = PhotoExtractorCore.remaining(candidates, known)

        val selection = PhotoExtractorCore.pickOldest(candidates, known, n)
        val paths = mutableListOf<String>()
        for (candidate in selection) {
            val dest = File(pulledDir, "img_${candidate.id}_${candidate.displayName}")
            try {
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(candidate.id.toString()).build()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                paths.add(dest.absolutePath)
            } catch (_: SecurityException) {
                // Permission revoked mid-batch: stop honestly, keep what succeeded.
                break
            } catch (_: Exception) {
                break
            }
        }

        if (paths.isNotEmpty()) {
            val updated = ledger.copy(
                transferredIds = (ledger.transferredIds + selection.map { it.id }).distinct(),
                batches = ledger.batches + 1,
                lastWhere = PhotoExtractorCore.LAST_TRANSFER_WHERE_ +
                    "${pulledDir.absolutePath} batch#${ledger.batches + 1}"
            )
            saveLedger(updated)
            evidenceFile.appendText(
                PhotoExtractorCore.LAST_TRANSFER_WHERE_ + "batch#${updated.batches} " +
                    "asked=$n pulled=${paths.size} ids=[${selection.joinToString(",") { it.id.toString() }}]\n" +
                    paths.joinToString("\n", postfix = "\n")
            )
        }

        val remainingAfter = PhotoExtractorCore.remaining(candidates, ledger.transferredIds.toSet() + selection.map { it.id })
        return BatchResult(
            existing = existing,
            pulled = paths.size,
            remaining = if (paths.isEmpty()) remainingBefore else remainingAfter,
            paths = paths
        )
    }
}
