package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FakeRegistrationApiTest {

    private lateinit var api: FakeRegistrationApi
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        api = FakeRegistrationApi(filesDir)
    }

    private fun sampleReg(name: String, phone: String) = Registration(
        "ignored", name, phone, 123L,
        Fingerprint("aid-1", "Pixel 6a", "Google", 34, "arm64-v8a"),
        Constraints(4096, true, 32)
    )

    @Test
    fun appendsOneJsonLineAndReturnsNonEmptyId() = runBlocking {
        val reg = sampleReg("محمد", "+201012345678")
        val res = api.register(reg)
        val id = res.getOrThrow()
        assertTrue(id.isNotEmpty())

        val lines = api.queueFile().readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"name\":\"محمد\""))
        assertTrue(lines[0].contains("\"phone\":\"+201012345678\""))
        assertTrue(lines[0].contains("\"id\":\"$id\""))
        assertTrue("line=$lines", lines[0].contains("\"fingerprint\":{"))
        assertTrue("line=$lines", lines[0].contains("\"model\":\"Pixel 6a\""))
        assertTrue("line=$lines", lines[0].contains("\"constraints\":{"))
        assertTrue("line=$lines", lines[0].contains("\"phantomCeiling\":\"32\""))
    }

    @Test
    fun eachAppendProducesDistinctId() = runBlocking {
        val reg = sampleReg("ali", "+201111111111")
        val id1 = api.register(reg).getOrThrow()
        val id2 = api.register(reg).getOrThrow()
        assertNotEquals(id1, id2)
        assertEquals(2, api.queueFile().readLines().count { it.isNotBlank() })
    }
}
