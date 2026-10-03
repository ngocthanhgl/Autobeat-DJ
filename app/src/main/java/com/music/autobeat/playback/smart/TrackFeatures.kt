/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (Autobeat adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into Autobeat -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.autobeat.playback.smart

import com.music.autobeat.data.TrackLog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Whole-track envelope, structure and key analysis, from the native DSP
 * analyzer (`native/analyzer/audio_analysis.cpp`).
 *
 * This answers "where does the music actually end, where can a transition
 * enter and leave, how loud is it there, and is anyone singing" — the
 * transition policy needs a beat grid (also produced here, from
 * autocorrelation) to know how to mix, and these features to know *where*,
 * and through the energy curve, whether an interior mix-out anchor would
 * skip silence or skip a minute of music.
 */
object TrackFeatures {

    /** True when the native library loaded. Analysis is optional, so this is a fact, not a fault. */
    val available: Boolean = runCatching { System.loadLibrary("autobeat_analysis") }.isSuccess

    /**
     * The rate the analyzer's window and hop constants assume, deliberately
     * low, because envelope and structure work needs time resolution rather
     * than bandwidth, and an eighth of the samples is an eighth of the work.
     */
    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 11_025.0 }

    /**
     * Analyses [samples], which must be mono float PCM at [sampleRate].
     *
     * Returns null when the native library is missing or the analyzer
     * declined the input. Callers treat that as "no evidence", which the
     * policy already degrades on.
     */
    fun analyze(samples: FloatArray, durationSeconds: Double): Features? {
        if (!available || samples.isEmpty()) return null
        val json = runCatching { nativeAnalyze(samples, sampleRate, durationSeconds) }
            .onFailure { TrackLog.w(TAG, "Native analysis failed", it) }
            .getOrNull() ?: return null
        return runCatching { parse(JSONObject(json)) }
            .onFailure { TrackLog.w(TAG, "Could not parse analysis output", it) }
            .getOrNull()
            ?.let { correctDoubleTime(it) }
    }

    /**
     * Double-time backstop, Kotlin side of the native 8th-hat guard
     * (`tempo_analysis.cpp`): same bass-band lag-ratio test, over the
     * stored low-energy curve instead of the native envelope. Catches what
     * the native guard cannot — cached analyses from before the native fix,
     * and JNI bridges that predate it. Pure (no I/O, no settings) so the
     * regression test can pin it with synthetic curves.
     *
     * Fires only above [DOUBLE_TIME_GUARD_BPM]: a winner down there can only
     * be the double of a true tempo at/below ~110, and the 140 line
     * (dubstep, half-time hip-hop) stays out of reach. Halving preserves
     * grid phase (every other gridline survives), so firstBeat stands and
     * only the downbeat spacing is rebuilt — picked off the halved grid by
     * the same strongest-bass rule the native downbeat pass uses.
     *
     * Two votes, like native: the bass lag-ratio plus broadband parity at
     * the winner's rate (a snare on the off-beats means true fast material,
     * bare hats mean a double-read). Both must agree.
     */
    fun correctDoubleTime(features: Features): Features {
        val bpm = features.bpm
        val interval = features.beatInterval.takeIf { it > 0 }
            ?: if (bpm > 0) 60.0 / bpm else 0.0
        if (!(bpm > DOUBLE_TIME_GUARD_BPM) || !(interval > 0)) return features
        val curve = features.lowEnergyCurve
        if (curve.size < 16) return features
        val lagCorr = lagCorrelation(curve, interval)
        val dblCorr = lagCorrelation(curve, interval * 2)
        if (!(dblCorr > lagCorr * 1.3 && dblCorr > 0.2)) return features
        if (!(broadbandParity(features.energyCurve, features.firstBeat, interval) > 1.5)) return features
        val halved = bpm / 2
        val halvedInterval = interval * 2
        val downbeats = rebuildDownbeats(features.firstBeat, halvedInterval, features.duration, curve)
            .ifEmpty { features.downbeats }
        TrackLog.d(TAG, "double-time guard: $bpm -> $halved (bass lag $lagCorr vs $dblCorr)")
        return features.copy(bpm = halved, beatInterval = halvedInterval, downbeats = downbeats)
    }

    /** Normalized lag correlation over a uniform ~20 Hz resampling of [curve]. */
    private fun lagCorrelation(curve: List<EnergySample>, lag: Double): Double {
        if (!(lag > 0) || curve.size < 8) return 0.0
        val start = curve.first().time
        val end = curve.last().time
        if (!(end > start) || !(lag < end - start)) return 0.0
        val step = 0.05
        val count = ((end - start) / step).toInt().coerceAtMost(6000)
        if (count < 16) return 0.0
        val values = DoubleArray(count + 1)
        var index = 0
        for (i in 0..count) {
            val t = start + i * step
            while (index + 1 < curve.size && curve[index + 1].time <= t) index++
            values[i] = curve[index].energy
        }
        val lagSteps = (lag / step).roundToInt().coerceAtLeast(1)
        if (lagSteps * 2 >= values.size) return 0.0
        var cross = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (i in lagSteps until values.size) {
            val left = values[i]
            val right = values[i - lagSteps]
            cross += left * right
            leftEnergy += left * left
            rightEnergy += right * right
        }
        return cross / sqrt(maxOf(1e-12, leftEnergy * rightEnergy))
    }

    /**
     * Max even/odd mean-energy ratio on the [beat] grid off [firstBeat].
     * Phase-free (max over the two parities): a double-read alternates full
     * groove against bare hats, true fast material answers with a snare.
     */
    private fun broadbandParity(curve: List<EnergySample>, firstBeat: Double, beat: Double): Double {
        if (!(beat > 0) || curve.size < 8) return 1.0
        val lastTime = curve.last().time
        val firstTime = curve.first().time
        var evenSum = 0.0
        var evenN = 0
        var oddSum = 0.0
        var oddN = 0
        var k = 0
        while (k <= 512) {
            val t = firstBeat + k * beat
            if (t > lastTime) break
            if (t >= firstTime) {
                val v = curveAt(curve, t, beat * 0.1)
                if (k % 2 == 0) {
                    evenSum += v
                    evenN++
                } else {
                    oddSum += v
                    oddN++
                }
            }
            k++
        }
        if (evenN == 0 || oddN == 0) return 1.0
        val even = evenSum / evenN
        val odd = oddSum / oddN
        if (!(even > 0) || !(odd > 0)) return 1.0
        return maxOf(even / odd, odd / even)
    }

    /**
     * Downbeats off the halved grid: four bar-phases from [firstBeat], the
     * one with the strongest bass underneath is beat one.
     */
    private fun rebuildDownbeats(
        firstBeat: Double,
        beat: Double,
        duration: Double,
        curve: List<EnergySample>,
    ): List<Double> {
        if (!(beat > 0) || !(duration > beat)) return emptyList()
        var bestPhase = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0..3) {
            var sum = 0.0
            var n = 0
            var k = 0
            while (k <= 512) {
                val t = firstBeat + (4 * k + phase) * beat
                if (t > duration) break
                if (t >= 0) {
                    sum += curveAt(curve, t, beat * 0.06)
                    n++
                }
                k++
            }
            val mean = if (n > 0) sum / n else Double.NEGATIVE_INFINITY
            if (mean > bestScore) {
                bestScore = mean
                bestPhase = phase
            }
        }
        return buildList {
            var k = 0
            while (k <= 512) {
                val t = firstBeat + (4 * k + bestPhase) * beat
                if (t > duration) break
                if (t >= 0) add(t)
                k++
            }
        }
    }

    private fun curveAt(curve: List<EnergySample>, t: Double, radius: Double): Double {
        var sum = 0.0
        var n = 0
        for (sample in curve) {
            if (sample.time < t - radius) continue
            if (sample.time > t + radius) break
            if (sample.energy.isFinite()) {
                sum += sample.energy
                n++
            }
        }
        return if (n > 0) sum / n else 0.0
    }

    /**
     * Converts mono float PCM from [inputRate] to [sampleRate] (or any other
     * target), with an anti-aliasing windowed-sinc filter — see
     * `native/analyzer/resampler.cpp`.
     *
     * Returns the input unchanged when the rates already match, and null when
     * the native library is missing or the rates are unusable.
     */
    fun resample(samples: FloatArray, inputRate: Double, outputRate: Double = sampleRate): FloatArray? {
        if (!available || samples.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        return nativeResample(samples, inputRate, outputRate).takeIf { it.isNotEmpty() }
    }

    /** The subset of the analyzer's output the transition policy reads. */
    data class Features(
        val duration: Double,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
        val key: String,
        val keyConfidence: Double,
        val audibleStartTime: Double,
        val pickupTime: Double,
        val introEndTime: Double,
        val outroStartTime: Double,
        val contentEndTime: Double,
        val mixInTime: Double,
        val mixOutTime: Double,
        val vocalProbability: Double,
        // Full-plan P4: master descriptors, re-emitted by the JNI bridge.
        val loudnessLufs: Double = -70.0,
        val peakDbfs: Double = -70.0,
        val dynamicRangeDb: Double = 0.0,
        val downbeats: List<Double>,
        val phraseBoundaries: List<Double>,
        val vocalActivityMask: List<Double>,
        val energyCurve: List<EnergySample>,
        val lowEnergyCurve: List<EnergySample>,
        val mixInCandidates: List<MixCandidate>,
        val mixOutCandidates: List<MixCandidate>,
        // v2 §2b: structural detector inputs. Transient — parsed for the
        // detector, never written to the store (see StructureDetector).
        val onsetTimes: List<Double> = emptyList(),
        val spectralCentroidCurve: List<EnergySample> = emptyList(),
        val energyCurveFine: List<EnergySample> = emptyList(),
    )

    fun parse(root: JSONObject): Features = Features(
        duration = root.optDouble("duration", 0.0).orZero(),
        bpm = root.optDouble("bpm", 0.0).orZero(),
        beatInterval = root.optDouble("beatInterval", 0.0).orZero(),
        firstBeat = root.optDouble("firstBeat", 0.0).orZero(),
        beatConfidence = root.optDouble("beatConfidence", 0.0).orZero(),
        key = root.optString("key", ""),
        keyConfidence = root.optDouble("keyConfidence", 0.0).orZero(),
        audibleStartTime = root.optDouble("audibleStartTime", 0.0).orZero(),
        pickupTime = root.optDouble("pickupTime", 0.0).orZero(),
        introEndTime = root.optDouble("introEndTime", 0.0).orZero(),
        outroStartTime = root.optDouble("outroStartTime", 0.0).orZero(),
        contentEndTime = root.optDouble("contentEndTime", 0.0).orZero(),
        mixInTime = root.optDouble("mixInTime", 0.0).orZero(),
        mixOutTime = root.optDouble("mixOutTime", 0.0).orZero(),
        vocalProbability = root.optDouble("vocalProbability", 0.0).orZero(),
        loudnessLufs = root.optDouble("loudnessLufs", -70.0),
        peakDbfs = root.optDouble("peakDbfs", -70.0),
        dynamicRangeDb = root.optDouble("dynamicRangeDb", 0.0),
        downbeats = root.doubles("downbeats"),
        phraseBoundaries = root.doubles("phraseBoundaries"),
        vocalActivityMask = root.doubles("vocalActivityMask"),
        energyCurve = root.energyCurve("energyCurve"),
        lowEnergyCurve = root.energyCurve("lowEnergyCurve"),
        mixInCandidates = root.cuePoints("mixInCandidates"),
        mixOutCandidates = root.cuePoints("mixOutCandidates"),
        onsetTimes = root.doubles("onsetTimes"),
        spectralCentroidCurve = root.energyCurve("spectralCentroidCurve"),
        energyCurveFine = root.energyCurve("energyCurveFine"),
    )

    private fun JSONObject.doubles(name: String): List<Double> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optDouble(index).takeIf { it.isFinite() }?.let(::add)
            }
        }
    }

    private fun JSONObject.energyCurve(name: String): List<EnergySample> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val point = array.optJSONObject(index) ?: continue
                val time = point.optDouble("t", Double.NaN)
                val energy = point.optDouble("e", Double.NaN)
                if (time.isFinite() && energy.isFinite()) add(EnergySample(time, energy))
            }
        }
    }

    private fun JSONObject.cuePoints(name: String): List<MixCandidate> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val point = array.optJSONObject(index) ?: continue
                val time = point.optDouble("t", Double.NaN)
                if (!time.isFinite()) continue
                add(
                    MixCandidate(
                        time = time,
                        score = point.optDouble("s", 0.0).orZero(),
                        type = point.optString("y", ""),
                    ),
                )
            }
        }
    }

    private const val TAG = "AutobeatTrackFeatures"

    /**
     * Double-time suspect floor: a winner above this can only be the double
     * of a true tempo at/below ~110 BPM. Mirrored in native
     * (`tempo_analysis.cpp` 8th-hat guard) — keep the two in sync.
     */
    const val DOUBLE_TIME_GUARD_BPM = 165.0

    @JvmStatic private external fun nativeAnalyze(
        samples: FloatArray,
        sampleRate: Double,
        duration: Double,
    ): String

    @JvmStatic private external fun nativeSampleRate(): Double

    @JvmStatic private external fun nativeResample(
        samples: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): FloatArray
}
