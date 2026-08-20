package com.example

import com.example.ui.viewmodel.isEmergencyPhrase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SOS matcher decides whether a guardian gets woken and told the user is in danger.
 * False positives are as harmful as misses, so both directions are pinned here.
 */
class EmergencyPhraseTest {

    @Test
    fun `real distress calls raise an emergency`() {
        listOf(
            "help me",
            "SOS",
            "sos please",
            "someone help",
            "I need help",
            "save me",
            "I fell",
            "I have fallen and cannot get up",
            "emergency",
            "I am in danger",
            "मदत करा",
            "वाचवा",
            "मदद करो",
            "बचाओ",
            "आणीबाणी"
        ).forEach {
            assertTrue("should be emergency: $it", isEmergencyPhrase(it))
        }
    }

    @Test
    fun `ordinary conversation does not raise an emergency`() {
        listOf(
            "can you help me understand artificial intelligence",
            "could you help me with this",
            "please help me find a good restaurant",
            "help me choose a shirt",
            "that was really helpful",
            "call the helpline",
            "you are helping me a lot",
            "what time is it",
            "navigate me to Pune railway station",
            "किती वाजले",
            "समोर काय आहे"
        ).forEach {
            assertFalse("should NOT be emergency: $it", isEmergencyPhrase(it))
        }
    }
}
