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
            ?.let { correctKey(it) }
    }

    /**
     * Second opinion on the native key, from the same chroma through a
     * different estimator (Temperley profiles, Pearson correlation, versus
     * native Krumhansl dot-product).
     *
     * An adjudicator, never an abstention: every analyzed track keeps a key
     * label, because the display and the user always get a number. A
     * confident native read stands, and agreement keeps the label. On
     * disagreement the Temperley vote wins only with a clear margin
     * ([KEY_OVERRULE_MARGIN]) — a coin-flip contest keeps the native read,
     * which is usually right on triadic pop. A missing chroma (cached
     * analyses from before the bridge emitted it) keeps the native label
     * untouched. Mixing safety does not depend on this: the planner already
     * neutralizes low-confidence keys at its scoring/shift floors, so a
     * best-guess label here is informative without ever driving a mix it
     * should not.
     */
    fun correctKey(features: Features): Features {
        val chroma = features.chroma
        if (chroma.size != 12 || chroma.sum() <= 0 || features.key.isBlank()) return features
        if (features.keyConfidence >= KEY_CONTESTED_CONFIDENCE) return features
        val native = parseKeyLabel(features.key) ?: return features
        val (root, mode, margin) = estimateKeyTemperley(chroma) ?: return features
        if (root == native.first && mode == native.second) return features
        if (margin < KEY_OVERRULE_MARGIN) {
            TrackLog.d(TAG, "key contest: native ${features.key} (${features.keyConfidence}) vs temperley $root/$mode kept native (margin $margin)")
            return features
        }
        val label = TEMPERLEY_ROOT_NAMES[root] + if (mode == 0) " major" else " minor"
        TrackLog.d(TAG, "key contest: native ${features.key} (${features.keyConfidence}) overruled by temperley $label (margin $margin)")
        return features.copy(key = label)
    }

    /**
     * Parses a native key label into (pitch class, mode). Sharp is '#' or
     * U+266F, flat 'b' or U+266D — compared by codepoint so the source stays
     * ASCII. Returns null when the label is not in the native format.
     */
    private fun parseKeyLabel(key: String): Pair<Int, Int>? {
        val mode = when {
            key.endsWith(" major") -> 0
            key.endsWith(" minor") -> 1
            else -> return null
        }
        val name = key.removeSuffix(" major").removeSuffix(" minor")
        if (name.isEmpty()) return null
        val base = when (name[0]) {
            'C' -> 0
            'D' -> 2
            'E' -> 4
            'F' -> 5
            'G' -> 7
            'A' -> 9
            'B' -> 11
            else -> return null
        }
        val accidental = when {
            name.length == 1 -> 0
            name.length == 2 && (name[1] == '#' || name[1].code == 0x266F) -> 1
            name.length == 2 && (name[1] == 'b' || name[1].code == 0x266D) -> -1
            else -> return null
        }
        return Pair((base + accidental + 12) % 12, mode)
    }

    /**
     * Pearson-correlation key estimate as (pitch class, mode, margin), where
     * margin is the best score minus the runner-up. Null when degenerate.
     */
    private fun estimateKeyTemperley(chroma: List<Double>): Triple<Int, Int, Double>? {
        if (chroma.size != 12) return null
        val mean = chroma.sum() / 12
        var bestRoot = -1
        var bestMode = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        for (root in 0..11) {
            for (mode in 0..1) {
                val profile = if (mode == 0) TEMPERLEY_MAJOR else TEMPERLEY_MINOR
                var xy = 0.0
                var xx = 0.0
                var yy = 0.0
                val profileMean = profile.sum() / 12
                for (pitch in 0..11) {
                    val x = chroma[pitch] - mean
                    val y = profile[(pitch + 12 - root) % 12] - profileMean
                    xy += x * y
                    xx += x * x
                    yy += y * y
                }
                if (!(xx > 0) || !(yy > 0)) continue
                val score = xy / sqrt(xx * yy)
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestRoot = root
                    bestMode = mode
                } else if (score > secondScore) {
                    secondScore = score
                }
            }
        }
        if (bestRoot < 0) return null
        return Triple(bestRoot, bestMode, bestScore - secondScore)
    }

    /**
     * Double-time backstop, Kotlin side of the native 8th-hat guard
     * (`tempo_analysis.cpp`).
     *
     * Catches what the native guard cannot — cached analyses from before the
     * native fix, and JNI bridges that predate it. Pure (no I/O, no settings)
     * so the regression test can pin it with synthetic onsets.
     *
     * Fires only above [DOUBLE_TIME_GUARD_BPM]: a winner down there can only
     * be the double of a true tempo at/below ~110, and the 140 line
     * (dubstep, half-time hip-hop) stays out of reach. Halving preserves
     * grid phase (every other gridline survives), so firstBeat stands and
     * only the downbeat spacing is rebuilt — picked off the halved grid by
     * onset phase.
     *
     * Both votes read the native flux-peak train ([Features.onsetTimes],
     * 86 fps), deliberately NOT the persisted energy curves: those are ~1 s
     * buckets on a ballad-length track and structurally cannot resolve the
     * 0.29 s alternation this adjudicates. A double-read packs a full groove
     * into each halved beat (sparse per claimed beat, busy per halved one);
     * true fast material stays busy at its own rate, so the pair separates
     * them without any amplitude information.
     */
    fun correctDoubleTime(features: Features): Features {
        val bpm = features.bpm
        val interval = features.beatInterval.takeIf { it > 0 }
            ?: if (bpm > 0) 60.0 / bpm else 0.0
        if (!(bpm > DOUBLE_TIME_GUARD_BPM) || !(interval > 0)) return features
        val onsets = features.onsetTimes.filter { it.isFinite() }.sorted()
        val duration = features.duration
        if (onsets.isEmpty() || !(duration > interval)) {
            TrackLog.d(TAG, "double-time guard: $bpm kept (no onset data to vote on)")
            return features
        }
        // Sparse per claimed beat, busy per halved beat: a full groove one
        // octave down. True fast material (dnb fills, four-floor) stays at
        // two-plus per beat at its own rate and never reaches the second arm.
        val perClaimed = onsetsPerBeat(onsets, duration, interval)
        val perHalved = onsetsPerBeat(onsets, duration, interval * 2)
        if (perClaimed < DOUBLE_SPARSE_PER_BEAT && perHalved > DOUBLE_BUSY_PER_HALF_BEAT) {
            val halved = bpm / 2
            val halvedInterval = interval * 2
            val downbeats = rebuildDownbeats(features.firstBeat, halvedInterval, duration, onsets)
                .ifEmpty { features.downbeats }
            TrackLog.d(TAG, "double-time guard: $bpm -> $halved (onsets $perClaimed/beat, $perHalved/half-beat)")
            return features.copy(bpm = halved, beatInterval = halvedInterval, downbeats = downbeats)
        }
        TrackLog.d(TAG, "double-time guard: $bpm kept (onsets $perClaimed/beat, $perHalved/half-beat)")
        return features
    }

    /** Mean onset count per grid cell of [interval] over [duration]. */
    private fun onsetsPerBeat(onsets: List<Double>, duration: Double, interval: Double): Double {
        if (!(interval > 0) || !(duration > 0)) return Double.NaN
        val beats = duration / interval
        if (!(beats >= 1)) return Double.NaN
        return onsets.count { it >= 0 && it <= duration } / beats
    }

    /**
     * Downbeats off the halved grid: four bar-phases from [firstBeat], the
     * one with the most onsets landing on it is beat one.
     */
    private fun rebuildDownbeats(
        firstBeat: Double,
        beat: Double,
        duration: Double,
        onsets: List<Double>,
    ): List<Double> {
        if (!(beat > 0) || !(duration > beat) || onsets.isEmpty()) return emptyList()
        var bestPhase = 0
        var bestScore = -1
        for (phase in 0..3) {
            var n = 0
            var k = 0
            while (k <= 512) {
                val t = firstBeat + (4 * k + phase) * beat
                if (t > duration) break
                if (t >= 0 && hasOnsetNear(onsets, t, DOWNBEAT_ONSET_RADIUS)) n++
                k++
            }
            if (n > bestScore) {
                bestScore = n
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

    /** True when the sorted onset train has a tick within [radius] of [t]. */
    private fun hasOnsetNear(sortedOnsets: List<Double>, t: Double, radius: Double): Boolean {
        val i = sortedOnsets.binarySearch(t)
        if (i >= 0) return true
        val ip = -i - 1
        return (ip < sortedOnsets.size && sortedOnsets[ip] - t <= radius) ||
            (ip > 0 && t - sortedOnsets[ip - 1] <= radius)
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
        val chroma: List<Double> = emptyList(),
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
        // Sum-normalized C..B pitch-class energy; absent on cached analyses
        // from before the bridge emitted it, which disables the second
        // opinion below instead of guessing.
        chroma = root.doubles("chroma"),
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

    /** Temperley (1999) key profiles, indexed by semitone above the root. */
    private val TEMPERLEY_MAJOR = doubleArrayOf(
        5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0,
    )
    private val TEMPERLEY_MINOR = doubleArrayOf(
        5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0,
    )

    /**
     * Sharp-spelled ASCII root names, parallel to the profiles above. Same
     * spelling family the native detector emits ("C# minor", "Bb major"),
     * and both [parseKeyLabel] here and [camelotOf] downstream accept '#'
     * and 'b', so an overrule label parses everywhere a native one does.
     */
    private val TEMPERLEY_ROOT_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    )

    private const val TAG = "AutobeatTrackFeatures"

    /**
     * Double-time suspect floor: a winner above this can only be the double
     * of a true tempo at/below ~110 BPM. Mirrored in native
     * (`tempo_analysis.cpp` 8th-hat guard) — keep the two in sync.
     */
    const val DOUBLE_TIME_GUARD_BPM = 165.0

    /**
     * Onset-density arms of the guard above: a double-read is sparse per
     * claimed beat (the groove lives one octave down) and busy per halved
     * beat. True fast material fails one arm or the other — dnb fills and
     * four-floor stay busy at their own rate, sparse ambient fails the
     * halved arm.
     */
    const val DOUBLE_SPARSE_PER_BEAT = 2.0
    const val DOUBLE_BUSY_PER_HALF_BEAT = 2.5

    /** Radius for snapping onsets onto rebuilt downbeat gridlines. */
    private const val DOWNBEAT_ONSET_RADIUS = 0.06

    /**
     * Key labels below this confidence are contested, not facts: the native
     * template match reports a margin, and a collapsed margin (sustained
     * pads, detuned chorus, relative major/minor sharing every pitch class)
     * means "abstain but labeled". Matches the planner's retune floor —
     * nothing acts on a key under it anyway.
     */
    const val KEY_CONTESTED_CONFIDENCE = 0.5

    /**
     * Minimum Temperley margin (best Pearson minus runner-up) to overrule a
     * contested native label. Below it the contest is a coin flip and the
     * native read stands — triadic pop at 0.4 native confidence is usually
     * right, and a wrong overrule is worse than a weak keep because the
     * planner's floors already neutralize weak keys. Every contest logs its
     * margin, so this tunes from real logs, not theory.
     */
    const val KEY_OVERRULE_MARGIN = 0.05

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
