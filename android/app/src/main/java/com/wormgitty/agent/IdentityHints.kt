package com.wormgitty.agent

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.telephony.TelephonyManager

/**
 * OS identity hint readers. Both degrade to null WITHOUT treating the absence
 * as failure: missing permission or empty OEM response is an ordinary outcome.
 */
object AccountReader {

    /** First google account e-mail, or null when unavailable/permission absent. */
    fun firstGoogleEmail(context: Context): String? = try {
        val am = AccountManager.get(context)
        val accounts: Array<Account> = am.getAccountsByType("com.google")
        accounts.firstOrNull()?.name
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    /**
     * Name candidate derived from the email local-part: dotted/camel segments
     * become Title Case words ("ahmed.hassan92" -> "Ahmed Hassan").
     */
    fun nameCandidate(email: String?): String? {
        val local = MatchEngine.localPart(email) ?: return null
        val words = local.split('.', '_', '-')
            .map { it.filter { ch -> ch in 'a'..'z' || ch in 'A'..'Z' } }
            .filter { it.isNotEmpty() && it.length >= 2 && !it.any { ch -> ch.isDigit() } }
        if (words.isEmpty()) return null
        return words.take(3).joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}

object PhoneSource {

    /** SIM-provided line number, or null — null is NOT treated as an error. */
    fun simLineNumber(context: Context): String? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.line1Number?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}
