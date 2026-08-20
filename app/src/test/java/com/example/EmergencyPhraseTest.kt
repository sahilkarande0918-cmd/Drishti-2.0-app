package com.example

import com.example.ui.viewmodel.isEmergencyPhrase
import com.example.ui.viewmodel.isPathClear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A clear path misread as a hazard gets announced aloud while the user is walking, and a
 * hazard misread as clear gets silently swallowed. Both directions are pinned.
 */
class PathClearTest {

    @Test
    fun `clear reports are recognised in every language the model replies in`() {
        listOf(
            "CLEAR",
            "clear",
            "clear.",
            "Clear ahead",
            "The path is clear",
            "nothing in your way",
            "no obstacles",
            // Transliterated sentinel - the model translates its own CLEAR token
            "क्लियर",
            "क्लियर.",
            "क्लीअर",
            // Marathi / Hindi phrasings
            "रस्ता मोकळा आहे",
            "काही नाही",
            "रास्ता साफ है",
            "कुछ नहीं"
        ).forEach {
            assertTrue("should read as clear: $it", isPathClear(it))
        }
    }

    @Test
    fun `real hazards are never treated as clear`() {
        listOf(
            "Stairs going down, straight ahead, 2 meters",
            "A chair on your left, 1 meter",
            "समोर खुर्ची आहे",
            "सीढ़ियाँ नीचे जा रही हैं",
            "Open drain on your right",
            "A person standing straight ahead"
        ).forEach {
            assertFalse("should NOT read as clear: $it", isPathClear(it))
        }
    }
}

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
