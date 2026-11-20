package com.wormgitty.agent

object PhoneNormalizer {

    const val DEFAULT_CC = "20"

    /** Normalizes Egyptian-default phone input to E.164, returns null when invalid. */
    fun normalize(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 10..15) return null
        val e164 = when {
            digits.startsWith(DEFAULT_CC) && digits.length >= 12 ->
                "+${DEFAULT_CC}${digits.removePrefix(DEFAULT_CC)}"
            digits.startsWith("0") ->
                "+${DEFAULT_CC}${digits.dropWhile { it == '0' }}"
            else -> "+$digits"
        }.removePrefix("+")
        if (e164.length !in 10..15 || !e164.all { it.isDigit() }) return null
        return "+$e164"
    }
}
