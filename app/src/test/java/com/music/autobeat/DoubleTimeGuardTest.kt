package com.music.autobeat

import com.music.autobeat.playback.smart.EnergySample
import com.music.autobeat.playback.smart.TrackFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The double-time guard pins the Another-Day-in-Paradise class of failure:
 * a ~102 BPM ballad with driving 8th hats read back above 200 BPM, and the
 * grid, downbeats and every phrase-snapped move inherited the error.
 *
 * Each test synthesizes the low (bass) and broadband energy curves the guard
 * actually votes on, so a retune of the thresholds lands here instead of in
 * a mix nobody can debug by listening.
 */
class DoubleTimeGuardTest {

    private fun pulseCurve(
        duration: Double,
        step: Double = 0.05,
        valueAt: (Double) -> Double,
    ): List<EnergySample> {
        val out = mutableListOf<EnergySample>()
        var t = 0.0
        while (t <= duration) {
            out.add(EnergySample(t, valueAt(t)))
            t += step
        }
        return out
    }

    private fun features(
        bpm: Double,
        beatInterval: Double,
        firstBeat: Double,
        duration: Double,
        low: List<EnergySample>,
        broad: List<EnergySample>,
    ) = TrackFeatures.Features(
        duration = duration,
        bpm = bpm,
        beatInterval = beatInterval,
        firstBeat = firstBeat,
        beatConfidence = 0.8,
        key = "",
        keyConfidence = 0.0,
        audibleStartTime = 0.0,
        pickupTime = 0.0,
        introEndTime = 0.0,
        outroStartTime = 0.0,
        contentEndTime = duration,
        mixInTime = 0.0,
        mixOutTime = duration,
        vocalProbability = 0.0,
        downbeats = emptyList(),
        phraseBoundaries = emptyList(),
        vocalActivityMask = emptyList(),
        energyCurve = broad,
        lowEnergyCurve = low,
        mixInCandidates = emptyList(),
        mixOutCandidates = emptyList(),
    )

    @Test
    fun `ballad double-read halves to true tempo with rebuilt downbeats`() {
        // True 102 BPM (0.588 s beats), read at 204 (0.294 s): kicks on every
        // other read-beat, bare hats between.
        val duration = 200.0
        val low = pulseCurve(duration) { t ->
            val d = ((t - 0.3) % 0.588 + 0.588) % 0.588
            if (minOf(d, 0.588 - d) <= 0.06) 1.0 else 0.02
        }
        val broad = pulseCurve(duration) { t ->
            val d = ((t - 0.3) % 0.588 + 0.588) % 0.588
            if (minOf(d, 0.588 - d) <= 0.06) 1.0 else 0.12
        }
        val fixed = TrackFeatures.correctDoubleTime(features(204.0, 0.294, 0.3, duration, low, broad))
        assertEquals(102.0, fixed.bpm, 1.0)
        assertEquals(0.588, fixed.beatInterval, 0.005)
        assertEquals(0.3, fixed.firstBeat, 1e-9)
        assertTrue("expected several rebuilt downbeats, got ${fixed.downbeats.size}", fixed.downbeats.size > 3)
        fixed.downbeats.zipWithNext { a, b -> assertEquals(2.353, b - a, 0.06) }
    }

    @Test
    fun `genuine fast kick-snare alternation is kept`() {
        // True 174 BPM dnb: kick and snare-bleed alternate in the bass band
        // (so the doubled lag correlates well down low), but the snare answers
        // on the off-beats in broadband — parity stays weak, no halve.
        val beat = 60.0 / 174.0
        val duration = 120.0
        fun bandAt(t: Double, weak: Double): Double {
            val bi = (t - 0.2) / beat
            if (abs(bi - bi.roundToInt()) > 0.12) return 0.05
            return if (bi.roundToInt() % 2 == 0) 1.0 else weak
        }
        val low = pulseCurve(duration) { bandAt(it, 0.35) }
        val broad = pulseCurve(duration) { bandAt(it, 0.9) }
        val fixed = TrackFeatures.correctDoubleTime(features(174.0, beat, 0.2, duration, low, broad))
        assertEquals(174.0, fixed.bpm, 1e-9)
        assertEquals(beat, fixed.beatInterval, 1e-9)
    }

    @Test
    fun `winner below the floor is untouched`() {
        val duration = 200.0
        val low = pulseCurve(duration) { t ->
            val d = ((t - 0.3) % 0.8 + 0.8) % 0.8
            if (minOf(d, 0.8 - d) <= 0.06) 1.0 else 0.02
        }
        val broad = pulseCurve(duration) { 0.5 }
        val fixed = TrackFeatures.correctDoubleTime(features(150.0, 0.4, 0.3, duration, low, broad))
        assertEquals(150.0, fixed.bpm, 1e-9)
    }

    @Test
    fun `no bass evidence never halves`() {
        val duration = 200.0
        val broad = pulseCurve(duration) { 0.5 }
        val fixed = TrackFeatures.correctDoubleTime(
            features(190.0, 60.0 / 190.0, 0.2, duration, emptyList(), broad),
        )
        assertEquals(190.0, fixed.bpm, 1e-9)
    }
}
