package com.wormgitty.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cand(id: Long, date: Long) =
    PhotoExtractorCore.Candidate(id = id, dateAdded = date, displayName = "img$id")

class PhotoExtractorCoreTest {

    private val five = listOf(
        cand(101, 1000), cand(102, 1010), cand(103, 1020),
        cand(104, 1030), cand(105, 1040)
    )

    /* ------------------------- oldest-first picking ------------------------ */

    @Test
    fun picksOldestTwoInDateOrder() {
        val got = PhotoExtractorCore.pickOldest(five, emptySet(), 2)
        assertEquals(listOf(101L, 102L), got.map { it.id })
    }

    @Test
    fun cursorLedgerSkipsAlreadyTakenIds() {
        val got = PhotoExtractorCore.pickOldest(five, setOf(101L, 102L), 2)
        assertEquals(listOf(103L, 104L), got.map { it.id })
    }

    @Test
    fun nLargerThanAvailableTakesAllAndStops() {
        val got = PhotoExtractorCore.pickOldest(five, emptySet(), 2000)
        assertEquals(5, got.size)
        // And with everything in the ledger, a subsequent pull takes none.
        val again = PhotoExtractorCore.pickOldest(five, got.map { it.id }.toSet(), 2000)
        assertTrue(again.isEmpty())
    }

    /* ------------------------------- dedup -------------------------------- */

    @Test
    fun ledgerGuardsAgainstRepetition() {
        val taken = mutableSetOf<Long>()
        val batch1 = PhotoExtractorCore.pickOldest(five, taken, 2).also { taken += it.map { c -> c.id } }
        val batch2 = PhotoExtractorCore.pickOldest(five, taken, 2).also { taken += it.map { c -> c.id } }
        val batch3 = PhotoExtractorCore.pickOldest(five, taken, 2).also { taken += it.map { c -> c.id } }
        val all = (batch1 + batch2 + batch3).map { it.id }
        assertEquals(all.size, all.distinct().size) // no repeats
        assertEquals(setOf(101L, 102L, 103L, 104L, 105L), all.toSet())
    }

    /* ------------------------------ remaining ----------------------------- */

    @Test
    fun remainingCountsFreshOnly() {
        assertEquals(5, PhotoExtractorCore.remaining(five, emptySet()))
        assertEquals(3, PhotoExtractorCore.remaining(five, setOf(101L, 102L)))
        assertEquals(0, PhotoExtractorCore.remaining(five, five.map { it.id }.toSet()))
    }

    /* ------------------------------ edge cases ---------------------------- */

    @Test
    fun zeroOrNegativeNTakesNothing() {
        assertTrue(PhotoExtractorCore.pickOldest(five, emptySet(), 0).isEmpty())
        assertTrue(PhotoExtractorCore.pickOldest(five, emptySet(), -3).isEmpty())
    }

    @Test
    fun tieBrokenByIdForDeterminism() {
        val tied = listOf(cand(9, 500), cand(7, 500), cand(8, 500))
        assertEquals(listOf(7L, 8L), PhotoExtractorCore.pickOldest(tied, emptySet(), 2).map { it.id })
    }

    /* --------------------------- ledger codec ----------------------------- */

    @Test
    fun ledgerRoundTripsThroughCodec() {
        val ledger = PhotoLedger(transferredIds = listOf(1, 2, 3), batches = 2, lastWhere = "x")
        val text = LedgerCodec.encode(ledger)
        assertEquals(ledger, LedgerCodec.decode(text))
    }

    @Test
    fun corruptedLedgerDecodesToNullSoFreshStartWins() {
        assertEquals(null, LedgerCodec.decode("{broken"))
        assertEquals(emptyList<Long>(), LedgerCodec.decode("{}")?.transferredIds)
    }
}
