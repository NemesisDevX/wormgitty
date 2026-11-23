package com.wormgitty.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReducerTest {

    private fun e(pkg: String, t: String = "t", x: String = "x", at: Long = 0) =
        NotifEntry(pkg = pkg, title = t, text = x, atMs = at)

    /* ------------------------- self-exclusion ---------------------------- */

    @Test
    fun selfPackageIsNeverRecorded() {
        assertTrue(NotifFilter.shouldRecord("com.wormgitty.agent", "com.android.shell"))
        assertFalse(NotifFilter.shouldRecord("com.wormgitty.agent", "com.wormgitty.agent"))
    }

    /* --------------------------- cap at CAP ------------------------------ */

    @Test
    fun olderEntriesAreDroppedBeyondCap() {
        val overflow = 50L
        val many = (1..NotificationsStore.CAP + 50).map { e("other.app", at = it.toLong()) }
        val capped = NotifFilter.cap(many)
        assertEquals(NotificationsStore.CAP, capped.size)
        assertEquals(overflow + 1, capped.first().atMs) // newest CAP survive; oldest dropped
        assertEquals(NotificationsStore.CAP + overflow, capped.last().atMs)
    }

    @Test
    fun underCapListUntouched() {
        val few = (1..10).map { e("other.app", at = it.toLong()) }
        assertEquals(few, NotifFilter.cap(few))
    }

    /* --------------------- simple-object conversion ---------------------- */

    @Test
    fun sideChannelTriplesBecomePlainEntries() {
        val entry = NotifFilter.entryOf(
            "com.other.chat",
            "Incoming",
            "hello there",
            42L
        )
        assertEquals("com.other.chat", entry.pkg)
        assertEquals("Incoming", entry.title)
        assertEquals("hello there", entry.text)
        assertEquals(42L, entry.atMs)
    }

    @Test
    fun nullTitleOrTextBecomeEmptyNotCrash() {
        val entry = NotifFilter.entryOf("pkg", null, null, 0L)
        assertEquals("", entry.title)
        assertEquals("", entry.text)
    }

    /* ------------------------- persistence codec -------------------------- */

    @Test
    fun notificationsJsonRoundTrips() {
        val list = listOf(e("a", at = 1), e("b", at = 2))
        val text = Wire.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(NotifEntry.serializer()), list
        )
        val back = Wire.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(NotifEntry.serializer()), text
        )
        assertEquals(list, back)
    }

    @Test
    fun corruptStoreDecodesToEmpty() {
        val parsed = runCatching {
            Wire.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(NotifEntry.serializer()),
                "{oops"
            )
        }.getOrNull()
        assertNull(parsed)
        assertTrue(parsed.isNullOrEmpty())
    }
}
