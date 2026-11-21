package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RegistrationStoreTest {

    private lateinit var store: RegistrationStore
    private lateinit var filesDir: File

    private val reg = Registration(
        id = "uuid-77",
        name = "محمد أحمد ünïcode",
        phone = "+201012345678",
        createdAtMs = 1_700_000_000_000,
        fingerprint = Fingerprint("aid-9", "Galaxy A14", "Samsung", 34, "arm64-v8a"),
        constraints = Constraints(3072, false, 32)
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = RegistrationStore(ctx)
        filesDir = ctx.filesDir
    }

    private fun rawFile(): File = File(filesDir, RegistrationStore.REGISTRATION_FILE)

    @Test
    fun roundTripsNestedFingerprintAndUnicodeName() {
        store.save(reg)
        assertEquals(reg, store.load())
        assertTrue(store.isRegistered())
    }

    @Test
    fun toleratesUnknownFutureFields() {
        store.save(reg)
        val injected = Wire.encode(reg).replaceFirst("{", "{\"someFutureField\":123,")
        rawFile().writeText(injected)
        assertEquals(reg, store.load())
    }

    @Test
    fun corruptFileLoadsAsNullInsteadOfCrashing() {
        store.save(reg)
        rawFile().writeText("{not json")
        assertNull(store.load())
        assertFalse(store.isRegistered())
    }
}
