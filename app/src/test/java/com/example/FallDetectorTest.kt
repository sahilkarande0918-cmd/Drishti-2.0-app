package com.example

import com.example.util.FallDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Replays accelerometer traces at 50Hz (20ms per reading), the rate the app now samples at.
 * A missed drop leaves a fallen user alone; a false alarm wakes a guardian. Both are pinned.
 */
class FallDetectorTest {

    private class Trace { val readings = mutableListOf<Double>()
        fun hold(g: Double, ms: Int) = apply { repeat(ms / 20) { readings += g } }
    }

    private fun alerts(trace: Trace): Int {
        val d = FallDetector()
        var t = 1_000L
        var count = 0
        for (g in trace.readings) { if (d.onReading(g, t)) count++; t += 20 }
        return count
    }

    @Test
    fun `a drop from hand height alerts once`() {
        val drop = Trace().hold(1.0, 500).hold(0.1, 300).hold(6.0, 40).hold(1.0, 2_000)
        assertEquals(1, alerts(drop))
    }

    @Test
    fun `a long drop from a large height alerts once`() {
        // Longer free fall and a harder landing, then a brief bounce before settling.
        val drop = Trace().hold(1.0, 500).hold(0.05, 700).hold(9.0, 40).hold(1.6, 200).hold(1.0, 2_000)
        assertEquals(1, alerts(drop))
    }

    @Test
    fun `a soft landing on carpet still alerts`() {
        val drop = Trace().hold(1.0, 300).hold(0.2, 350).hold(3.0, 60).hold(1.0, 2_000)
        assertEquals(1, alerts(drop))
    }

    @Test
    fun `putting the phone down on a table does not alert`() {
        val placed = Trace().hold(1.0, 500).hold(0.8, 200).hold(1.3, 80).hold(1.0, 2_000)
        assertEquals(0, alerts(placed))
    }

    @Test
    fun `walking does not alert`() {
        val walk = Trace()
        repeat(40) { walk.hold(0.7, 240).hold(1.6, 240) }
        assertEquals(0, alerts(walk))
    }

    @Test
    fun `a drop that is caught and kept moving does not alert`() {
        val caught = Trace().hold(1.0, 300).hold(0.1, 250).hold(3.5, 40)
        repeat(40) { caught.hold(1.8, 100).hold(0.5, 100) } // being handled, never at rest
        assertFalse(alerts(caught) > 0)
    }
}
