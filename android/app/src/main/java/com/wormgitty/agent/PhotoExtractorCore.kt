package com.wormgitty.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Pure batch-extraction math shared by the MediaStore puller and tests.
 *
 * Cursor semantics: [PhotoLedger.transferredIds] is the permanent "already
 * taken" set — an id inside it can never be extracted twice, regardless of
 * process restarts.
 */
object PhotoExtractorCore {

    const val LAST_TRANSFER_WHERE_ = "LAST_TRANSFER_WHERE_"

    @Serializable
    data class Candidate(val id: Long, val dateAdded: Long, val displayName: String)

    /** Oldest-first selection: DATE_ADDED ascending, skipping every known id. */
    fun pickOldest(
        candidates: List<Candidate>,
        transferredIds: Set<Long>,
        n: Int
    ): List<Candidate> {
        if (n <= 0) return emptyList()
        return candidates
            .filter { it.id !in transferredIds }
            .sortedWith(compareBy({ it.dateAdded }, { it.id }))
            .take(n)
    }

    /** Remaining extractable count = total fresh candidates minus ledger. */
    fun remaining(candidates: List<Candidate>, transferredIds: Set<Long>): Int =
        candidates.count { it.id !in transferredIds }
}

/** Durable ledger: one JSON file, survives force-stop; ids never reused. */
@Serializable
data class PhotoLedger(
    val transferredIds: List<Long> = emptyList(),
    val batches: Int = 0,
    val lastWhere: String = ""
)

object LedgerCodec {
    private val serializer = PhotoLedger.serializer()

    fun encode(ledger: PhotoLedger): String = Wire.json.encodeToString(serializer, ledger)

    fun decode(text: String): PhotoLedger? =
        runCatching { Wire.json.decodeFromString(serializer, text) }.getOrNull()
}
