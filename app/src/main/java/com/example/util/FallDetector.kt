package com.example.util

/**
 * Recognises a real phone drop - slipping from the hand and hitting the ground - from
 * accelerometer magnitude, and ignores quick handling movements.
 *
 * A genuine drop has a signature a jerk of the wrist cannot produce:
 *  1. SUSTAINED free fall. Falling ~1m takes ~450ms; even a waist-height drop is ~350ms of
 *     near-zero G. A flick or a sudden movement dips low for only tens of milliseconds.
 *  2. A HARD impact straight after the fall ends.
 *  3. The phone then LIES STILL for a sustained period, not just one calm reading.
 *
 * The previous version fired on a single low reading, a 2.5G spike and a single "at rest"
 * reading, so ordinary quick movements raised an SOS. Every stage now has a duration, and
 * the stillness check requires the whole window to stay calm.
 *
 * Pure logic, no Android types, so traces can be replayed in FallDetectorTest.
 * Expects ~50Hz readings (SENSOR_DELAY_GAME).
 */
class FallDetector(
    /** Below this is free fall. */
    private val freeFallG: Double = 0.35,
    /** Minimum continuous free fall. Quick movements never stay this low this long. */
    private val minFreeFallMs: Long = 200,
    /** Impact must reach this. */
    private val impactG: Double = 3.0,
    /** Impact must come this soon after the free fall ends. */
    private val impactWithinMs: Long = 250,
    /** Bouncing and settling allowed right after impact before stillness is judged. */
    private val settleMs: Long = 400,
    /** How long the phone must then lie still. */
    private val stillMs: Long = 1_500,
    /** Band that counts as lying still (1G at rest). */
    private val stillLowG: Double = 0.85,
    private val stillHighG: Double = 1.15,
    /** Abandon a candidate that never settles (it was caught or picked up). */
    private val candidateTimeoutMs: Long = 5_000
) {
    private var freeFallStartedAt = 0L
    private var lastFreeFallAt = 0L
    private var qualifyingFallEndedAt = 0L

    private var impactAt = 0L
    private var stillSince = 0L

    /** Feed one reading. Returns true exactly once per detected drop. */
    fun onReading(gForce: Double, nowMs: Long): Boolean {
        // ---- Stage 3: waiting for the phone to lie still after an impact ----
        if (impactAt > 0) {
            val sinceImpact = nowMs - impactAt
            if (sinceImpact > candidateTimeoutMs) {
                reset()
                return false
            }
            if (sinceImpact < settleMs) return false

            if (gForce in stillLowG..stillHighG) {
                if (stillSince == 0L) stillSince = nowMs
                if (nowMs - stillSince >= stillMs) {
                    reset()
                    return true
                }
            } else {
                // Any movement restarts the stillness window - a phone being handled
                // never stays calm for the full period.
                stillSince = 0L
            }
            return false
        }

        // ---- Stage 1: measure how long free fall lasts ----
        if (gForce < freeFallG) {
            if (freeFallStartedAt == 0L) freeFallStartedAt = nowMs
            lastFreeFallAt = nowMs
            return false
        }
        if (freeFallStartedAt != 0L) {
            // Free fall just ended; keep it only if it lasted long enough to be a drop.
            if (lastFreeFallAt - freeFallStartedAt >= minFreeFallMs) {
                qualifyingFallEndedAt = lastFreeFallAt
            }
            freeFallStartedAt = 0L
        }

        // ---- Stage 2: a hard impact right after a qualifying fall ----
        if (qualifyingFallEndedAt > 0) {
            if (nowMs - qualifyingFallEndedAt > impactWithinMs) {
                qualifyingFallEndedAt = 0L
            } else if (gForce >= impactG) {
                impactAt = nowMs
                stillSince = 0L
                qualifyingFallEndedAt = 0L
            }
        }
        return false
    }

    fun reset() {
        freeFallStartedAt = 0L
        lastFreeFallAt = 0L
        qualifyingFallEndedAt = 0L
        impactAt = 0L
        stillSince = 0L
    }
}
