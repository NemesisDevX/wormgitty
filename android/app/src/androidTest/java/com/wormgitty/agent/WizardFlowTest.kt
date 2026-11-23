package com.wormgitty.agent

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * End-to-end happy path on the current wizard: LOADING -> IDENTITY -> PHONE ->
 * permission sections (skipped, mock perms) -> SUMMARY -> finish. With mock_perms the
 * transport is FakeRegistrationApi, so the seal succeeds offline and runs the
 * vanish NOW (veil -> aliases off -> clear pendingHide -> finish).
 */
@RunWith(AndroidJUnit4::class)
class WizardFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /** LOADING -> IDENTITY -> PHONE -> first PERM section. Caller launches the scenario. */
    private fun reachPermStep() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("name_input").fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
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
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("perm_skip").fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0f }
        }
        compose.waitForIdle()
    }

    @Test
    fun skipWithAllGrantedAdvancesExactlyOneSection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MOCK_PERMS, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            reachPermStep()
            // All granted (GreenChecker): skipping section 1 must land on the
            // CAMERA section — regression for the unkeyed-remember auto-advance.
            compose.onNodeWithTag("perm_skip").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.reason_sec_camera))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithTag("finish").fetchSemanticsNodes().isEmpty().let {
                assertTrue("skip must not leap to SUMMARY", it)
            }
        }
    }

    @Test
    fun gateSectionSkipNeedsExplicitConfirm() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MOCK_PERMS, true)
            .putExtra(MainActivity.EXTRA_DENY_GATES, true)
        ActivityScenario.launch<MainActivity>(intent).use {
            reachPermStep()
            // Sections 0–2 carry no gate keys — free skip.
            repeat(PERM_SECTIONS.size - 1) {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("perm_skip").fetchSemanticsNodes()
                        .any { it.boundsInRoot.width > 0f }
                }
                compose.onNodeWithTag("perm_skip").performScrollTo().performClick()
                compose.waitForIdle()
            }
            // Protection section: gate keys denied -> skip opens the impact
            // dialog and must NOT advance before confirmation.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("missing_gate_count").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("perm_skip").performScrollTo().performClick()
            try {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("gate_skip_confirm").fetchSemanticsNodes().isNotEmpty()
                }
            } catch (t: Throwable) {
                compose.onRoot(useUnmergedTree = true).printToLog("GATE_DUMP")
                throw t
            }
            compose.onNodeWithTag("gate_skip_confirm").assertExists()
            compose.onNodeWithTag("gate_skip_back").assertExists()
            compose.onNodeWithTag("perm_skip").assertExists() // still on the section

            // Back keeps the user on the section; skip re-opens the dialog.
            compose.onNodeWithTag("gate_skip_back").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("perm_skip").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("gate_skip_confirm").performClick()
            compose.waitForIdle()

            // Confirmed skip lands on SUMMARY with the denied gates shown red.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("finish").fetchSemanticsNodes()
                    .any { it.boundsInRoot.width > 0f }
            }
            compose.onNodeWithTag("summary_missing_gate_OVERLAY").assertExists()
            compose.onNodeWithTag("summary_missing_gate_DEVICE_ADMIN").assertExists()
        }
    }

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
            // LOADING auto-advances to IDENTITY after ~1.8s — wait for a laid-out node.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("name_input").fetchSemanticsNodes()
                    .any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            }
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

            // Permission sections — wait for each section's skip button, then skip.
            repeat(PERM_SECTIONS.size) {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("perm_skip").fetchSemanticsNodes()
                        .any { it.boundsInRoot.width > 0f }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("perm_skip").performScrollTo().performClick()
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
