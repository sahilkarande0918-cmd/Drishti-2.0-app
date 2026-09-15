package com.example

import com.example.util.FallDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays accelerometer traces at 50Hz (20ms per reading), the rate the app samples at.
 *
 * Reported in the field: "the slightest sudden movement triggers SOS". A false alarm wakes a
 * guardian and blares an alarm in public, so the ordinary-movement cases matter as much as
 * the real drops - and a missed real drop leaves a fallen user alone.
 */
class FallDetectorTest {

    private class Trace {
        val readings = mutableListOf<Double>()
        fun hold(g: Double, ms: Int) = apply { repeat(ms / 20) { readings += g } }
    }

    private fun alerts(trace: Trace): Int {
        val d = FallDetector()
        var t = 1_000L
        var count = 0
        for (g in trace.readings) {
            if (d.onReading(g, t)) count++
            t += 20
        }
        return count
    }

    // ------------------------------------------------------------- real drops: alert

    @Test
    fun `phone slips from hand while walking and hits the ground`() {
        // ~1m fall = ~450ms free fall, hard landing, lies still.
        val drop = Trace().hold(1.2, 600).hold(0.1, 460).hold(7.0, 40).hold(1.0, 2_500)
        assertEquals(1, alerts(drop))
    }

    @Test
    fun `drop from waist height alerts`() {
        val drop = Trace().hold(1.0, 500).hold(0.15, 360).hold(5.0, 40).hold(1.0, 2_500)
        assertEquals(1, alerts(drop))
    }

    @Test
    fun `drop that bounces before settling alerts once`() {
        val drop = Trace().hold(1.0, 500).hold(0.05, 500).hold(8.0, 40)
            .hold(1.7, 120).hold(0.6, 100).hold(1.0, 2_500)
        assertEquals(1, alerts(drop))
    }

    // ------------------------------------------------------- ordinary movement: never

    @Test
    fun `a quick sudden jerk of the hand does not alert`() {
        // Brief dip then a spike, then held still in the hand.
        val jerk = Trace().hold(1.0, 500).hold(0.2, 60).hold(3.5, 40).hold(1.0, 3_000)
        assertEquals(0, alerts(jerk))
    }

    @Test
    fun `swinging or shaking the phone does not alert`() {
        val shake = Trace().hold(1.0, 300)
        repeat(30) { shake.hold(0.3, 80).hold(3.2, 60).hold(1.0, 60) }
        shake.hold(1.0, 3_000)
        assertEquals(0, alerts(shake))
    }

    @Test
    fun `quickly lifting the phone up to the ear does not alert`() {
        val lift = Trace().hold(1.0, 500).hold(0.4, 150).hold(2.6, 100).hold(1.0, 3_000)
        assertEquals(0, alerts(lift))
    }

    @Test
    fun `tossing the phone onto a bed does not alert`() {
        // Real free fall, but a soft landing that never reaches a hard impact.
        val bed = Trace().hold(1.0, 400).hold(0.1, 300).hold(1.8, 80).hold(1.0, 3_000)
        assertEquals(0, alerts(bed))
    }

    @Test
    fun `putting the phone down on a table does not alert`() {
        val placed = Trace().hold(1.0, 500).hold(0.8, 200).hold(1.3, 80).hold(1.0, 3_000)
        assertEquals(0, alerts(placed))
    }

    @Test
    fun `walking and running do not alert`() {
        val walk = Trace()
        repeat(60) { walk.hold(0.5, 200).hold(2.2, 160).hold(1.0, 120) }
        assertEquals(0, alerts(walk))
    }

    @Test
    fun `a drop that is caught and handled does not alert`() {
        val caught = Trace().hold(1.0, 300).hold(0.1, 300).hold(3.5, 40)
        repeat(40) { caught.hold(1.8, 100).hold(0.6, 100) } // being handled, never still
        assertEquals(0, alerts(caught))
    }

    @Test
    fun `a jump with the phone in a pocket does not alert when they keep moving`() {
        val jump = Trace().hold(1.0, 400).hold(0.2, 280).hold(3.2, 60)
        repeat(20) { jump.hold(1.4, 150).hold(0.7, 150) } // walking on afterwards
        assertEquals(0, alerts(jump))
    }
}
