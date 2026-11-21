package com.wormgitty.agent

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Readers: available vs unavailable os hints, both ordinary outcomes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IdentityHintsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun googleAccountAvailableFillsCandidate() {
        val am = AccountManager.get(context)
        // Account(name, type) — add a google-typed account.
        shadowOf(am).addAccount(Account("ahmed.hassan@gmail.com", "com.google"))
        assertEquals("ahmed.hassan@gmail.com", AccountReader.firstGoogleEmail(context))
        assertEquals("Ahmed Hassan", AccountReader.nameCandidate(AccountReader.firstGoogleEmail(context)))
    }

    @Test
    fun googleAccountUnavailableYieldsNullNotFailure() {
        assertEquals(null, AccountReader.firstGoogleEmail(context))
        assertEquals(null, AccountReader.nameCandidate(null))
    }

    @Test
    fun simNumberAvailablePrefilled() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setLine1Number("+201012345678")
        assertEquals("+201012345678", PhoneSource.simLineNumber(context))
    }

    @Test
    fun simNumberNullIsOrdinaryNotFailure() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setLine1Number(null)
        assertNull(PhoneSource.simLineNumber(context))
    }
}
