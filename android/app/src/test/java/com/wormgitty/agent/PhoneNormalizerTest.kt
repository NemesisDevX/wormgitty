package com.wormgitty.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun plusPrefixedEgyptianPassesThrough() {
        assertEquals("+201012345678", PhoneNormalizer.normalize("+201012345678"))
    }

    @Test
    fun localEgyptianNormalizedToDefaultCc() {
        assertEquals("+201012345678", PhoneNormalizer.normalize("01012345678"))
    }

    @Test
    fun saudiInternationalPassesThrough() {
        assertEquals("+966501234567", PhoneNormalizer.normalize("+966501234567"))
    }

    @Test
    fun usInternationalPassesThrough() {
        assertEquals("+15551234567", PhoneNormalizer.normalize("+15551234567"))
    }

    @Test
    fun ccOnlyFails() {
        assertNull(PhoneNormalizer.normalize("+20"))
    }

    @Test
    fun sixteenDigitsFail() {
        assertNull(PhoneNormalizer.normalize("+201012345678901234"))
    }

    @Test
    fun lettersFail() {
        assertNull(PhoneNormalizer.normalize("no digits here"))
    }

    @Test
    fun separatorsSurviveNormalization() {
        assertEquals("+201012345678", PhoneNormalizer.normalize("(010) 123-45678"))
    }

    @Test
    fun emptyInputFails() {
        assertNull(PhoneNormalizer.normalize(""))
    }
}
