package com.wormgitty.agent

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.endsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * StatusActivity wipe wiring: wipe_btn is connected to WipePrompt's
 * double-gated dialog when device admin is active, and routes to admin
 * activation (toast + settings) when it is not.
 */
@RunWith(AndroidJUnit4::class)
class WipeFlowTest {

    @Before
    fun setUp() {
        OperatorActions.isAdminEnabledForTest = true
        // Unattended runs sleep between Gradle stages; dialogs need window focus.
        val auto = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().uiAutomation
        auto.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        auto.executeShellCommand("wm dismiss-keyguard").close()
    }

    @After
    fun tearDown() { OperatorActions.isAdminEnabledForTest = null }

    @Test
    fun wipeButtonOpensDoubleGateDialog() {
        ActivityScenario.launch(StatusActivity::class.java).use {
            onView(withText(R.string.wipe_btn)).perform(click())
            // Gate 1: the blast-radius dialog.
            onView(withText(R.string.wipe_confirm_dialog))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            // Shot hook: -e hold_ms <ms> keeps gate 1 open for host screencaps.
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("hold_ms")?.toLongOrNull()?.let { Thread.sleep(it) }
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
            // Gate 2: typed-confirmation prompt (EditText in the dialog).
            onView(withClassName(endsWith("EditText")))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
        }
    }

    @Test
    fun wipeWithoutAdminNeverOpensConfirmDialog() {
        OperatorActions.isAdminEnabledForTest = false
        ActivityScenario.launch(StatusActivity::class.java).use {
            onView(withText(R.string.wipe_btn)).perform(click())
            onView(withText(R.string.wipe_confirm_dialog)).check(doesNotExist())
        }
    }
}
