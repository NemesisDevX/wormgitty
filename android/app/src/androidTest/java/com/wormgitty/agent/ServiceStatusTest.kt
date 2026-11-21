package com.wormgitty.agent

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun serviceReachesStartedAndWritesHealthSnapshot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WormSvc.start(context)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var snapshot: HealthState? = null
        while (System.nanoTime() < deadline) {
            snapshot = HealthStore.read(context)
            val beat = snapshot?.lastBeat ?: 0L
            if (snapshot != null && beat >= snapshot.startedAt && beat > 0) break
            TimeUnit.MILLISECONDS.sleep(250)
        }
        assertNotNull("health.json never appeared", snapshot)
        assertTrue(
            "state=${snapshot!!.state}",
            snapshot.state == HealthStore.STATE_STARTED ||
                snapshot.state == HealthStore.STATE_RUNNING
        )
        assertTrue("startedAt must be stamped", snapshot.startedAt > 0)
        assertTrue("lastBeat must be stamped", snapshot.lastBeat >= snapshot.startedAt)
    }
}
