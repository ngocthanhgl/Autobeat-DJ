package com.music.autobeat

import com.music.autobeat.playback.smart.TrackFeatures
import com.music.autobeat.playback.smart.camelotLabel
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
    fun `sparse claimed but quiet halved rate is kept`() {        // Sparse ambient at a true 180: one onset per claimed beat and only
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

    @Test
    fun `half-time trap read doubles to the mixable grid`() {
        // True 140 BPM half-time, read at 70 (0.857 s beats): kick, snare and
        // subdivided hats pack ~5 onsets per claimed beat, ~2.5 per doubled
        // one — busy claimed, grooved double. Doubles to 140 with downbeats
        // on the 4-beat (1.714 s) cycle.
        val duration = 120.0
        val firstBeat = 0.2
        val interval = 60.0 / 70.0
        val onsets = buildList {
            var t = firstBeat
            while (t <= duration) {
                add(t)
                add(t + 0.15)
                add(t + 0.30)
                add(t + 0.45)
                add(t + 0.60)
                t += interval
            }
        }
        val fixed = TrackFeatures.correctDoubleTime(features(70.0, interval, firstBeat, duration, onsets))
        assertEquals(140.0, fixed.bpm, 1.0)
        assertEquals(60.0 / 140.0, fixed.beatInterval, 0.005)
        assertTrue("expected several rebuilt downbeats, got ${fixed.downbeats.size}", fixed.downbeats.size > 3)
        fixed.downbeats.zipWithNext { a, b -> assertEquals(1.714, b - a, 0.06) }
    }

    @Test
    fun `true slow ballad below the half floor is kept`() {
        // True 70 BPM ballad: ~1.5 onsets per claimed beat never reaches the
        // 4.0 busy arm, so the double gate leaves it alone.
        val duration = 200.0
        val interval = 60.0 / 70.0
        val onsets = buildList {
            var t = 0.3
            var k = 0
            while (t <= duration) {
                add(t)
                if (k % 2 == 0) add(t + 0.2)
                t += interval
                k++
            }
        }
        val fixed = TrackFeatures.correctDoubleTime(features(70.0, interval, 0.3, duration, onsets))
        assertEquals(70.0, fixed.bpm, 1e-9)
        assertEquals(interval, fixed.beatInterval, 1e-9)
    }

    /** Exact Krumhansl C-major shape: both estimators must agree C major. */
    private fun cMajorChroma(): List<Double> {
        val major = listOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val sum = major.sum()
        return major.map { it / sum }
    }

    /**
     * Squared Temperley D-major template: the Pearson match at (D, major) is
     * ~1.0 while every other root/mode sits far below, so the margin clears
     * the overrule floor and a contested native C major loses the label.
     */
    private fun dMajorDecisiveChroma(): List<Double> {
        val template = listOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
        val rotated = List(12) { p -> template[(p + 12 - 2) % 12] }
        val squared = rotated.map { it * it }
        val sum = squared.sum()
        return squared.map { it / sum }
    }

    @Test
    fun `decisive temperley disagreement overrules a contested label`() {
        val fixed = TrackFeatures.correctKey(
            features(100.0, 0.6, 0.0, 200.0, emptyList(), key = "C major", keyConfidence = 0.2, chroma = dMajorDecisiveChroma()),
        )
        assertEquals("D major", fixed.key)
    }

    @Test
    fun `coin-flip contest keeps the native read`() {
        // Near-flat ramp: every template correlates weakly and the top two
        // sit within a hair of each other, so whatever wins, the margin veto
        // keeps the contested native label instead of flipping a coin.
        val ramp = List(12) { p -> (1.0 + 0.001 * p) / (12.0 + 0.001 * 66.0) }
        val fixed = TrackFeatures.correctKey(
            features(100.0, 0.6, 0.0, 200.0, emptyList(), key = "C major", keyConfidence = 0.2, chroma = ramp),
        )
        assertEquals("C major", fixed.key)
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

    @Test
    fun `all twelve ascii native names parse - a silent parse failure keeps the label`() {
        // The JNI bridge keeps bytes 32-126 only, so the native table spells
        // black keys ASCII ("C#", "Bb"). adjudicateKey returns the label
        // unchanged when parsing fails — indistinguishable from a keep — so
        // the assertion demands the overrule: adversarial decisive-D-major
        // chroma must flip every label to "D major". Any name that does not
        // parse comes back native and fails here instead of mislabeling a
        // semitone off in production.
        val names = listOf(
            "C", "C#", "D", "Eb", "E", "F",
            "F#", "G", "Ab", "A", "Bb", "B",
        )
        for (name in names) {
            for (mode in listOf("major", "minor")) {
                val fixed = TrackFeatures.correctKey(
                    features(
                        100.0, 0.6, 0.0, 200.0, emptyList(),
                        key = "$name $mode", keyConfidence = 0.2, chroma = dMajorDecisiveChroma(),
                    ),
                )
                assertEquals("$name $mode should parse and lose to decisive D major", "D major", fixed.key)
            }
        }
    }

    @Test
    fun `camelot labels stay inside the wheel alphabet`() {
        // The pill's trailing A/B is the ring (A minor-side, B major-side),
        // never the pitch — pin the alphabet so a future edit cannot smuggle
        // a root letter into the code slot and re-create the "only A and B"
        // misread.
        val alphabet = Regex("^(1[0-2]|[1-9])[AB]$")
        val roots = listOf(
            "C", "C#", "D", "Eb", "E", "F",
            "F#", "G", "Ab", "A", "Bb", "B",
            "C♯", "E♭", "F♯", "A♭", "B♭",
        )
        for (root in roots) {
            for (mode in listOf("major", "minor")) {
                val label = camelotLabel("$root $mode")
                assertTrue("$root $mode -> $label", label != null && alphabet.matches(label))
            }
        }
    }
}
