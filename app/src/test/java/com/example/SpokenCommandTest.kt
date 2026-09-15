package com.example

import com.example.ui.viewmodel.isVisualQuestion
import com.example.ui.viewmodel.normalizeSpokenCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real speech arrives from the Marathi recogniser in Devanagari with English loanwords
 * transliterated. These pin that such phrasing reaches the right command - the gap that
 * let "कॅमेरा ओपन कर" fall through to chat while adb tests (in Latin) passed.
 */
class SpokenCommandTest {

    @Test
    fun `devanagari loanwords become matchable latin`() {
        val c = normalizeSpokenCommand("कॅमेरा ओपन कर आणि सांग पुढे काय आहे")
        assertTrue(c.contains("camera open"))
        // Original Marathi is kept so pure-Marathi matchers still hit.
        assertTrue(c.contains("कॅमेरा"))
        assertTrue(normalizeSpokenCommand("लाईव्ह नेव्हिगेशन सुरू कर").contains("live navigation suru"))
        assertTrue(normalizeSpokenCommand("लाइव्ह नेव्हिगेशन चालू कर").contains("live navigation chalu"))
    }

    @Test
    fun `plain marathi and english pass through unchanged`() {
        val m = "किती वाजले"
        assertTrue(normalizeSpokenCommand(m) == m)
        assertTrue(normalizeSpokenCommand("Open Camera") == "open camera")
    }

    @Test
    fun `questions about surroundings are visual`() {
        listOf(
            "सांग पुढे काय आहे", "समोर काय आहे", "काय दिसतंय", "आजूबाजूला काय आहे",
            "what is in front of me", "what do you see"
        ).forEach { assertTrue("should open camera: $it", isVisualQuestion(it)) }
    }

    @Test
    fun `ordinary conversation is not visual`() {
        listOf(
            "मला AI बद्दल सांग", "describe artificial intelligence", "आज कोणता वार आहे",
            "तू कोण आहेस", "मला एक विनोद सांग", "what is the date"
        ).forEach { assertFalse("should stay conversation: $it", isVisualQuestion(it)) }
    }
}
