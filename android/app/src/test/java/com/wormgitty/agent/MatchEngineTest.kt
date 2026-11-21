package com.wormgitty.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchEngineTest {

    /* ------------------------------- phone ------------------------------- */

    @Test
    fun phoneMatchWhenEnteredEqualsSimTail() {
        val c = MatchEngine.checkPhone("01012345678", "+201012345678")
        assertEquals(MatchEngine.Outcome.MATCH, c.outcome)
    }

    @Test
    fun phoneMismatchIsYellowNotBlocking() {
        val c = MatchEngine.checkPhone("01099999999", "+201012345678")
        assertEquals(MatchEngine.Outcome.MISMATCH, c.outcome)
    }

    @Test
    fun phoneNullSimIsNoSignalNeverFailure() {
        val c = MatchEngine.checkPhone("01012345678", null)
        assertEquals(MatchEngine.Outcome.NO_SIGNAL, c.outcome)
        val blank = MatchEngine.checkPhone("01012345678", "")
        assertEquals(MatchEngine.Outcome.NO_SIGNAL, blank.outcome)
    }

    @Test
    fun phoneTrunkZeroAndCountryCodeNormalizeEqually() {
        assertEquals("+201012345678".let {
            MatchEngine.normalizePhone("20" + "01012345678".trimStart('0'))
        }, MatchEngine.normalizePhone("+20 101 234 5678"))
    }

    /* -------------------------------- name ------------------------------- */

    @Test
    fun nameConfirmedWhenLatinPrefixOverlapsEmail() {
        val c = MatchEngine.checkNameAgainstEmail("Ahmed Samir", "ahmed.samir92@gmail.com")
        assertEquals(MatchEngine.Outcome.MATCH, c.outcome)
    }

    @Test
    fun nameWarnsWhenPrefixDiffers() {
        val c = MatchEngine.checkNameAgainstEmail("Mostafa Ali", "ahmed.samir92@gmail.com")
        assertEquals(MatchEngine.Outcome.MISMATCH, c.outcome)
    }

    @Test
    fun nameGreyWhenNoAccountAvailable() {
        val c = MatchEngine.checkNameAgainstEmail("Ahmed Samir", null)
        assertEquals(MatchEngine.Outcome.NO_SIGNAL, c.outcome)
    }

    @Test
    fun nameGreyForArabicOnlyRendering() {
        val c = MatchEngine.checkNameAgainstEmail("أحمد سمير", "ahmed.samir92@gmail.com")
        assertEquals(MatchEngine.Outcome.NO_SIGNAL, c.outcome)
    }

    /* ---------------------------- prefill name --------------------------- */

    @Test
    fun nameCandidateBuildsTitleCaseWordsFromLocalPart() {
        assertEquals("Ahmed Hassan", AccountReader.nameCandidate("ahmed.hassan92@gmail.com"))
        assertEquals(null, AccountReader.nameCandidate("+201234567890@gcarrier"))
    }

    @Test
    fun nameCandidateNullWithoutEmail() {
        assertEquals(null, AccountReader.nameCandidate(null))
    }
}
