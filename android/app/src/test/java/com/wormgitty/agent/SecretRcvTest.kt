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
class SecretRcvTest {

    private lateinit var rcv: SecretRcv
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rcv = SecretRcv()
    }

    @Test
    fun secretCodeBroadcastLaunchesStatusActivity() {
        val intent = Intent("android.provider.Telephony.SECRET_CODE")
            .setData(android.net.Uri.parse("android_secret_code://2468"))
            .setPackage(context.packageName)
        rcv.onReceive(context, intent)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(StatusActivity::class.java.name, started.component!!.className)
    }
}
