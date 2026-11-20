package com.wormgitty.agent

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SealedDeviceAccessTest {

    @Test
    fun statusActivityReachableWhenLauncherHidden() {
        ActivityScenario.launch<StatusActivity>(
            Intent(ApplicationProvider.getApplicationContext(), StatusActivity::class.java)
        ).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { act -> assertTrue(!act.isFinishing) }
        }
    }

    @Test
    fun serviceReachesStartedAndWritesHeartbeat() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WormSvc.start(context)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var alive = false
        var beat = false
        while (System.nanoTime() < deadline && !(alive && beat)) {
            alive = ServiceAlive.started
            beat = WormSvc.heartbeatFile(context).length() > 0
            if (!(alive && beat)) TimeUnit.MILLISECONDS.sleep(250)
        }
        assertTrue("service did not reach STARTED", alive)
        assertTrue("no heartbeat row written", beat)
    }
}
