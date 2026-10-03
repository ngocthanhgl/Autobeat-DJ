package com.music.autobeat

import com.music.autobeat.playback.smart.TrackFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The double-time guard pins the Another-Day-in-Paradise class of failure:
 * a ~102 BPM ballad with driving 8th hats read back above 200 BPM, and the
 * grid, downbeats and every phrase-snapped move inherited the error.
 *
 * The guard votes off the native flux-peak train ([TrackFeatures.onsetTimes],
 * 86 fps) — the only signal with sub-beat resolution. The persisted energy
 * curves are ~1 s buckets on a ballad-length track and cannot resolve the
 * 0.29 s alternation, so the tests synthesize onsets, not curves: a
 * double-read is sparse per claimed beat and busy per halved beat, true fast
 * material stays busy at its own rate.
 */
class DoubleTimeGuardTest {

    private fun features(
        bpm: Double,
        beatInterval: Double,
        firstBeat: Double,
        duration: Double,
        onsets: List<Double>,
        key: String = "",
        keyConfidence: Double = 0.0,
        chroma: List<Double> = emptyList(),
    ) = TrackFeatures.Features(
        duration = duration,
        bpm = bpm,
        beatInterval = beatInterval,
        firstBeat = firstBeat,
        beatConfidence = 0.8,
        key = key,
        keyConfidence = keyConfidence,
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
        energyCurve = emptyList(),
        lowEnergyCurve = emptyList(),
        mixInCandidates = emptyList(),
        mixOutCandidates = emptyList(),
        onsetTimes = onsets,
        chroma = chroma,
    )

    @Test
    fun `ballad double-read halves to true tempo with rebuilt downbeats`() {
        // True 102 BPM (0.588 s beats), read at 204 (0.294 s): a flux peak on
        // every read-beat (kick/hat coincident on evens, hats on odds) plus
        // comping stabs after most odds. Sparse per claimed beat (1.3), busy
        // per halved one (2.6).
        val duration = 200.0
        val firstBeat = 0.3
        val interval = 0.294
        val onsets = buildList {
            var k = 0
            while (true) {
                val t = firstBeat + k * interval
                if (t > duration) break
                add(t)
                if (k % 2 == 1) add(t + 0.1)
                k++
            }
        }
        val fixed = TrackFeatures.correctDoubleTime(features(204.0, interval, firstBeat, duration, onsets))
        assertEquals(102.0, fixed.bpm, 1.0)
        assertEquals(0.588, fixed.beatInterval, 0.005)
        assertEquals(0.3, fixed.firstBeat, 1e-9)
        assertTrue("expected several rebuilt downbeats, got ${fixed.downbeats.size}", fixed.downbeats.size > 3)
        fixed.downbeats.zipWithNext { a, b -> assertEquals(2.353, b - a, 0.06) }
    }

    @Test
    fun `genuine fast material with dense onsets is kept`() {
        // True 174 BPM dnb: kick, snare and two hats per beat — four onsets
        // per claimed beat, so the sparse arm never fires.
        val beat = 60.0 / 174.0
        val duration = 120.0
        val onsets = buildList {
            var t = 0.2
            while (t <= duration) {
                add(t)
                add(t + 0.08)
                add(t + 0.17)
                add(t + 0.26)
                t += beat
            }
        }
        val fixed = TrackFeatures.correctDoubleTime(features(174.0, beat, 0.2, duration, onsets))
        assertEquals(174.0, fixed.bpm, 1e-9)
        assertEquals(beat, fixed.beatInterval, 1e-9)
    }

    @Test
    fun `winner below the floor is untouched`() {
        val duration = 200.0
        val onsets = buildList {
            var t = 0.3
            while (t <= duration) {
                add(t)
                t += 0.4
            }
        }
        val fixed = TrackFeatures.correctDoubleTime(features(150.0, 0.4, 0.3, duration, onsets))
        assertEquals(150.0, fixed.bpm, 1e-9)
    }

    @Test
    fun `no onset data never halves`() {
        val duration = 200.0
        val fixed = TrackFeatures.correctDoubleTime(
            features(190.0, 60.0 / 190.0, 0.2, duration, emptyList()),
        )
        assertEquals(190.0, fixed.bpm, 1e-9)
    }

    @Test
    fun `sparse claimed but quiet halved rate is kept`() {
        // Sparse ambient at a true 180: one onset per claimed beat and only
        // two per halved beat — the busy arm fails, so no halve.
        val interval = 60.0 / 180.0
        val duration = 120.0
        val onsets = buildList {
            var t = 0.2
            while (t <= duration) {
                add(t)
                t += interval
            }
        }
        val fixed = TrackFeatures.correctDoubleTime(features(180.0, interval, 0.2, duration, onsets))
        assertEquals(180.0, fixed.bpm, 1e-9)
    }

    /** Exact Krumhansl C-major shape: both estimators must agree C major. */
    private fun cMajorChroma(): List<Double> {
        val major = listOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val sum = major.sum()
        return major.map { it / sum }
    }

    @Test
    fun `agreeing estimators keep a contested label`() {
        val fixed = TrackFeatures.correctKey(
            features(100.0, 0.6, 0.0, 200.0, emptyList(), key = "C major", keyConfidence = 0.2, chroma = cMajorChroma()),
        )
        assertEquals("C major", fixed.key)
    }

    @Test
    fun `missing chroma keeps the native label`() {
        val fixed = TrackFeatures.correctKey(
            features(100.0, 0.6, 0.0, 200.0, emptyList(), key = "F minor", keyConfidence = 0.1),
        )
        assertEquals("F minor", fixed.key)
    }

    @Test
    fun `confident native label stands whatever the chroma`() {
        val flat = List(12) { 1.0 / 12 }
        val fixed = TrackFeatures.correctKey(
            features(100.0, 0.6, 0.0, 200.0, emptyList(), key = "G major", keyConfidence = 0.7, chroma = flat),
        )
        assertEquals("G major", fixed.key)
    }
}
