package com.example.util

/**
 * Recognises a phone drop from accelerometer magnitude: free fall, then a hard impact, then
 * the phone coming to rest.
 *
 * Pure logic with no Android types, so the exact sequence a real drop produces can be
 * replayed in a unit test - a physical drop cannot be scripted, and this code previously
 * failed in the field without anything having noticed.
 *
 * Needs the accelerometer at game rate (~50Hz). The impact spike of a phone hitting the floor
 * lasts roughly 10-40ms; at the normal ~5Hz rate it was almost never sampled, so a real drop
 * usually produced a free fall followed straight by "at rest" and no alert.
 */
class FallDetector(
    private val freeFallG: Double = 0.45,
    private val impactG: Double = 2.5,
    /** Longest gap between the last free-fall sample and the impact sample. */
    private val impactWindowMs: Long = 600,
    /** How long after impact the phone must read roughly 1G before we alert. */
    private val restAfterImpactMs: Long = 1_000,
    /** Give up waiting for rest after this long (phone kept moving = it was caught/picked up). */
    private val restTimeoutMs: Long = 6_000
) {
    private var lastFreeFallAt = 0L
    private var impactAt = 0L

    /** Feed one reading. Returns true exactly once per detected drop. */
    fun onReading(gForce: Double, nowMs: Long): Boolean {
        if (gForce < freeFallG) {
            lastFreeFallAt = nowMs
        }

        if (gForce > impactG && lastFreeFallAt > 0 && nowMs - lastFreeFallAt <= impactWindowMs) {
            impactAt = nowMs
        }

        if (impactAt > 0) {
            val sinceImpact = nowMs - impactAt
            if (sinceImpact > restTimeoutMs) {
                reset()
                return false
            }
            if (sinceImpact >= restAfterImpactMs && gForce in 0.7..1.3) {
                reset()
                return true
            }
        }
        return false
    }

    fun reset() {
        lastFreeFallAt = 0L
        impactAt = 0L
    }
}
