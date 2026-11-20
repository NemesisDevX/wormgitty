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

    @Test
    fun appendsOneJsonLineAndReturnsNonEmptyId() = runBlocking {
        val reg = Registration("ignored", "محمد", "+201012345678", 123L)
        val res = api.register(reg)
        val id = res.getOrThrow()
        assertTrue(id.isNotEmpty())

        val lines = api.queueFile().readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"name\":\"محمد\""))
        assertTrue(lines[0].contains("\"phone\":\"+201012345678\""))
        assertTrue(lines[0].contains("\"id\":\"$id\""))
    }

    @Test
    fun eachAppendProducesDistinctId() = runBlocking {
        val reg = Registration("", "ali", "+201111111111", 1L)
        val id1 = api.register(reg).getOrThrow()
        val id2 = api.register(reg).getOrThrow()
        assertNotEquals(id1, id2)
        assertEquals(2, api.queueFile().readLines().count { it.isNotBlank() })
    }
}
