package com.wormgitty.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameValidatorTest {

    @Test
    fun oneCharFails() {
        assertFalse(NameValidator.isValid("أ"))
    }

    @Test
    fun twoCharsPass() {
        assertTrue(NameValidator.isValid("سم"))
    }

    @Test
    fun fortyCharsPass() {
        assertTrue(NameValidator.isValid("a".repeat(40)))
    }

    @Test
    fun fortyOneCharsFail() {
        assertFalse(NameValidator.isValid("a".repeat(41)))
    }

    @Test
    fun whitespaceTrimmedBeforeValidation() {
        assertTrue(NameValidator.isValid("  سم  "))
    }
}
