package com.wormgitty.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun egyptianMobileGetsCountryCode() {
        assertEquals("+201012345678", PhoneNormalizer.normalize("01012345678"))
    }

    @Test
    fun nineDigitsRejected() {
        assertNull(PhoneNormalizer.normalize("010123456"))
    }

    @Test
    fun sixteenDigitsRejected() {
        assertNull(PhoneNormalizer.normalize("0101234567890123"))
    }

    @Test
    fun alreadyCountryCodedKeepsPlusForm() {
        assertEquals("+201012345678", PhoneNormalizer.normalize("201012345678"))
    }

    @Test
    fun e164PassthroughValid() {
        assertEquals("+14155552671", PhoneNormalizer.normalize("+1 (415) 555-2671"))
    }

    @Test
    fun emptyInputRejected() {
        assertNull(PhoneNormalizer.normalize(""))
    }
}
