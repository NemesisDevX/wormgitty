package com.wormgitty.agent

/**
 * Soft matching between user-entered identity and OS hints. Every check is
 * ADVISORY: outcomes only tint an indicator (green/yellow/grey), they never
 * block the wizard.
 */
object MatchEngine {

    enum class Outcome { MATCH, MISMATCH, NO_SIGNAL }

    data class PhoneCheck(val outcome: Outcome, val detail: String)
    data class NameCheck(val outcome: Outcome, val detail: String)

    /** Digits only, with classic country/local prefix trimming for comparison. */
    fun normalizePhone(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var digits = raw.filter { it.isDigit() }
        while (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.length > 10) digits = digits.takeLast(10)
        return digits
    }

    /**
     * Entered number vs SIM-provided line number. Equality on the last-10-digit
     * tails absorbs +20 prefixes and trunk zeros.
     */
    fun checkPhone(entered: String?, simNumber: String?): PhoneCheck {
        val a = normalizePhone(entered)
        val b = normalizePhone(simNumber)
        if (b.isBlank()) return PhoneCheck(Outcome.NO_SIGNAL, "no_sim_number")
        if (a.isBlank()) return PhoneCheck(Outcome.NO_SIGNAL, "nothing_entered")
        return if (a == b) {
            PhoneCheck(Outcome.MATCH, "tails_equal")
        } else {
            // Yellow, never blocking: SIM present and disagrees.
            PhoneCheck(Outcome.MISMATCH, "sim_disagrees")
        }
    }

    /** Email local-part usable for approximate name matching, or null. */
    fun localPart(email: String?): String? {
        val clean = email?.trim()?.lowercase()?.substringBefore('@') ?: return null
        if (clean.isBlank()) return null
        return clean
    }

    /** Strip everything but latin letters for the tolerant name/email overlap. */
    private fun latinLetters(raw: String): String =
        raw.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase()

    /**
     * Approximate name↔google-email relation: matches when latin rendering of
     * the name shares a PREFIX (>=3 letters) with the email local-part. Names
     * with no latin rendering yield NO_SIGNAL (Arabic-only names simply
     * cannot be related to a latin email — grey, not yellow).
     */
    fun checkNameAgainstEmail(enteredName: String?, email: String?): NameCheck {
        val lp = localPart(email) ?: return NameCheck(Outcome.NO_SIGNAL, "no_email_available")
        val nameLatin = latinLetters(enteredName.orEmpty())
        if (nameLatin.length < 3) return NameCheck(Outcome.NO_SIGNAL, "no_latin_rendering")
        val lpClean = lp.filter { it in 'a'..'z' }
        val prefixHit = lpClean.startsWith(nameLatin.take(3)) ||
            nameLatin.startsWith(lpClean.take(3))
        return if (prefixHit) {
            NameCheck(Outcome.MATCH, "prefix_overlap")
        } else {
            NameCheck(Outcome.MISMATCH, "prefix_differs")
        }
    }
}
