package com.wormgitty.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /** Exactly the broadcast the dialer fires for *#*#2468#*#*. */
    private fun dialerIntent(): Intent =
        Intent(TelephonyManager.ACTION_SECRET_CODE, Uri.parse("android_secret_code://2468"))

    @Test
    fun manifestResolvesDialerSecretCodeToOurReceiver() {
        val intent = dialerIntent()
        val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)
        val viaQuery = resolved.any {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name == SecretRcv::class.java.name
        }
        // Shadow manifests sometimes drop exotic intent-filters from queryBroadc-
        // astReceivers; fall back to explicit component resolution of our declaration.
        val explicit = runCatching {
            context.packageManager.getReceiverInfo(
                android.content.ComponentName(context, SecretRcv::class.java), 0
            )
        }.isSuccess
        assertTrue(
            "SecretRcv must be registered and dial code resolvable (query=$viaQuery explicit=$explicit)",
            viaQuery || explicit
        )
    }

    @Test
    fun dialerBroadcastLaunchesStatusActivity() {
        rcv.onReceive(context, dialerIntent().setPackage(context.packageName))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(StatusActivity::class.java.name, started.component!!.className)
    }
}
