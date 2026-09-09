package com.example

import com.example.util.DetectionStabilizer
import com.example.util.ObstacleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stabilizer is what stands between a one-frame misclassification and a blind user
 * being told there is a refrigerator in front of them. Both directions are pinned: a
 * flicker must be swallowed, and a real object must still get through quickly.
 */
class DetectionStabilizerTest {

    private fun obstacle(
        label: String,
        proximity: ObstacleDetector.Proximity = ObstacleDetector.Proximity.CLOSE
    ) = ObstacleDetector.Obstacle(
        label = label,
        direction = ObstacleDetector.Direction.AHEAD,
        proximity = proximity,
        score = 0.9f,
        heightFraction = 0.4f
    )

    @Test
    fun `a single stray detection is never reported`() {
        val s = DetectionStabilizer()
        // One frame hallucinates a fridge among otherwise steady detections.
        assertTrue(s.confirm(listOf(obstacle("bed"))).none { it.label == "refrigerator" })
        val flicker = s.confirm(listOf(obstacle("bed"), obstacle("refrigerator")))
        assertTrue(
            "a fridge seen in one frame must not be announced",
            flicker.none { it.label == "refrigerator" }
        )
    }

    @Test
    fun `an object that persists is reported`() {
        val s = DetectionStabilizer()
        repeat(2) { s.confirm(listOf(obstacle("bed"))) }
        val confirmed = s.confirm(listOf(obstacle("bed")))
        assertEquals(listOf("bed"), confirmed.map { it.label })
    }

    @Test
    fun `something about to be walked into needs fewer confirmations`() {
        val s = DetectionStabilizer()
        s.confirm(listOf(obstacle("person", ObstacleDetector.Proximity.VERY_CLOSE)))
        val confirmed = s.confirm(listOf(obstacle("person", ObstacleDetector.Proximity.VERY_CLOSE)))
        assertEquals(
            "an imminent collision should not wait for the full window",
            listOf("person"),
            confirmed.map { it.label }
        )
    }

    @Test
    fun `an object that disappears stops being reported`() {
        val s = DetectionStabilizer()
        repeat(3) { s.confirm(listOf(obstacle("chair"))) }
        // Chair gone for a full window; nothing should be confirmed from an empty frame.
        repeat(5) { s.confirm(emptyList()) }
        assertTrue(s.confirm(emptyList()).isEmpty())
    }
}
