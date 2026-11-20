package com.wormgitty.agent

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** End-to-end happy path: fresh install walks the wizard through sealing. */
@RunWith(AndroidJUnit4::class)
class WizardFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun freshInstallReachesLockAndSealsDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MOCK_PERMS, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitForIdle()
            compose.onNodeWithTag("next_intro").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("name_input").performTextInput("Ø§Ø®ØªØ¨Ø§Ø±")
            compose.waitForIdle()
            compose.onNodeWithTag("next_name").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("next_checklist").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("phone_input").performTextInput("01012345678")
            compose.waitForIdle()
            compose.onNodeWithTag("next_phone").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("lock_btn").assertExists()
            compose.onNodeWithTag("lock_btn").performClick()
            compose.waitForIdle()

            // Step 6 completes async (2s veil); wait, then assert sealed state.
            TimeUnit.SECONDS.sleep(4)
            // The activity finished itself; PM state is process-global.
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val pkg = ctx.packageName
            val pm = ctx.packageManager
            assertEquals(
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.LauncherBoost"))
            )
            assertEquals(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.LauncherHidden"))
            )

            // Service heartbeats to disk.
            val found = booleanArrayOf(false)
            for (i in 0 until 20) {
                found[0] = WormSvc.heartbeatFile(ctx).length() > 0
                if (found[0]) break
                TimeUnit.MILLISECONDS.sleep(500)
            }
            assertTrue("heartbeat file missing", found[0])
        }
    }
}
