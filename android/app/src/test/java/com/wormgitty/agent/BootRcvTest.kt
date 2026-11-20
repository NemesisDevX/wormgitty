package com.wormgitty.agent

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BootRcvTest {

    private lateinit var rcv: BootRcv
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rcv = BootRcv()
    }

    @Test
    fun bootCompletedStartsWormSvc() {
        rcv.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(WormSvc::class.java.name, started.component!!.className)
    }

    @Test
    fun packageReplacedAlsoStartsWormSvc() {
        rcv.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(WormSvc::class.java.name, started.component!!.className)
    }
}
