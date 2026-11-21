package com.wormgitty.agent

object PhoneNormalizer {

    const val DEFAULT_CC = "20"

    /** E.164 body after '+': [1-9] then 9..14 more digits (total 10..15). */
    private val E164_BODY = Regex("^\\+[1-9]\\d{9,14}$")

    /**
     * Normalizes loose dialer input to strict E.164. Country code +20 is prepended
     * only for local numbers (leading 0 after stripping). Returns null when the
     * result can never be a valid subscriber number.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.none { it.isDigit() }) return null
        val candidate = when {
            trimmed.startsWith("+") -> "+${trimmed.filter { it.isDigit() }}"
            trimmed.filter { it.isDigit() }.startsWith("0") ->
                "+$DEFAULT_CC${trimmed.filter { it.isDigit() }.dropWhile { it == '0' }}"

            else -> "+${trimmed.filter { it.isDigit() }}"
        }
        if (!E164_BODY.matches(candidate)) return null
        return candidate
    }
}
