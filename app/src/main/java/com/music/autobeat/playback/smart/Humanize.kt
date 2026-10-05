package com.music.autobeat.playback.smart

import com.music.autobeat.data.settings.AppSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The humanizer: R1 per-play jitter + R2 wildcard moves for the DJ reactive
 * engine.
 *
 * The planner is deterministic — the same pair under the same settings plans
 * the same mix every time (same overlap, same armed effects, same glide).
 * That reads as a good crossfade, not a DJ. Real booth work varies the trivia
 * (overlap by a beat, a slightly pushed rate, a cue a breath off the grid) and
 * occasionally pulls a punctuation move (a throw, a brake, a backspin) nobody
 * scheduled. This file does both, inside rails the planner already accepts:
 * the anchor timing ([TransitionPlan.transitionStart], `shouldStart`) and the
 * harmonic math (key shift) are never touched — only the ride differs.
 *
 * Two deliberate limits:
 * - Surgical/structural voices stay deterministic on timing: PLAIN_DISSOLVE,
 *   HARD_CUT and GAPLESS return the plan untouched, and LOOP_CUT_DROP /
 *   LOOP_ROLL take only the effect-intensity scales (a jittered loop length
 *   would desync the vamp schedule the renderer already armed). A cut is
 *   a cut; jittering its timing reads as sloppy, not human.
 * - Draws roll once per pair and are re-applied to every per-tick re-plan.
 *   [planTransition] runs every tick while the window is open, so re-rolling
 *   per call would churn the fingerprint and slide the marker. Consequence:
 *   a repeat-all lap replays the same mix for a pair it already drew.
 */
class HumanizeState {
    /** Every new pair increments this; mixed into the seed so laps differ. */
    var mixes: Int = 0
    /** Pair the cached draws belong to; a change re-rolls. */
    var pairDrawsFor: String? = null
    var draws: HumanDraws? = null
    /** No wildcard fires twice in a row — the second sighting is a habit. */
    var lastWildcard: String? = null
    /** Blends to wait after a wildcard, mirroring EFFECT_COOLDOWN_BLENDS. */
    var wildcardCooldown: Int = 0
}

/**
 * One pair's worth of spontaneity. Rolled once, re-applied to each refined
 * plan, so the fingerprint stays stable across ticks.
 */
data class HumanDraws(
    /** Overlap nudge in outgoing beats, -2..2. */
    val fadeDeltaBeats: Int,
    /** Incoming-rate push/pull, ±0.0015 multiplicative. */
    val rateDelta: Double,
    /** Snap the cue to the nearest downbeat within ±1 beat. */
    val cueSnap: Boolean,
    /** Effect-intensity scales, applied only when the arm is non-zero. */
    val echoScale: Double,
    val reverbScale: Double,
    val sweepScale: Double,
    /** Bass-swap fraction nudge, ±0.08. */
    val swapDelta: Double,
    /** Wildcard move: \"throw\", \"brake\", \"backspin\", or null. */
    val wildcard: String?,
    /**
     * Backspin hand: 1 = one decisive pull, 2-3 = the hand re-grabbing
     * mid-spin. Only voiced on backspin plans; ignored everywhere else.
     */
    val spinGrabs: Int,
)

/** Wildcard fire rate per eligible blend. */
const val WILDCARD_CHANCE = 0.08

/**
 * Humanizes [plan] for one pair: jitter the trivia, maybe arm a wildcard.
 * Returns the plan to arm plus a one-line note for the verdict log (null
 * when nothing moved, so untouched blends leave no trace).
 */
fun humanizePlan(
    plan: TransitionPlan,
    out: TrackAnalysis?,
    next: TrackAnalysis?,
    st: HumanizeState,
    pairKey: String,
): Pair<TransitionPlan, String?> {
    val isBlend = plan.transitionStyle == TransitionStyle.DJ_BLEND ||
        plan.transitionStyle == TransitionStyle.DJ_FILTER ||
        plan.transitionStyle == TransitionStyle.ECHO_REVERB_OUT
    // Loop voices take the wet scales only (below): their lengths are already
    // armed into the vamp schedule, so timing draws would desync the renderer.
    val isLoop = plan.transitionStyle == TransitionStyle.LOOP_CUT_DROP ||
        plan.transitionStyle == TransitionStyle.LOOP_ROLL
    if (!isBlend && !isLoop && !plan.backspin) {
        return plan to null
    }
    val outBeatSec = out?.beatInterval?.takeIf { it > 0.0 }
        ?: out?.bpm?.takeIf { it > 0.0 }?.let { 60.0 / it }
    // No grid, no beat draws — but the level draws can still apply.
    val draws = drawsFor(plan, out, next, outBeatSec, st, pairKey) ?: return plan to null

    // Backspin plans: the hand only. Timing, rate and cue are the spin's own
    // geometry (window, drop landing); the one human axis is single pull vs
    // re-grabbed stutter.
    if (plan.backspin) {
        if (draws.spinGrabs <= 1) return plan to null
        // Literature juggle: the re-grabbed stutter is a battle move, not
        // seasoning — gate it like one. No vocal under the hand in the spin
        // window (a scratch over singing is heckling), and the flicks land
        // on beats: grabs quantized to every 2nd outgoing beat across the
        // spin window, CDJ loop-roll style. A juggle is a big moment, so it
        // buys a longer silence after itself than a plain wildcard.
        val spinStart = plan.transitionEnd - plan.spinSeconds
        val sung = out?.let { vocalActivityBetween(it, spinStart, plan.transitionEnd) } ?: 0.0
        if (sung >= 0.3) return plan to null
        val grabs = if (outBeatSec != null && outBeatSec > 0 && plan.spinSeconds > 0) {
            (plan.spinSeconds / (2 * outBeatSec)).roundToInt().coerceIn(2, 3)
        } else {
            draws.spinGrabs
        }
        st.wildcardCooldown = maxOf(st.wildcardCooldown, 4)
        return plan.copy(spinGrabs = grabs) to "seed=${st.mixes} grabs=$grabs juggle"
    }

    var humanized = plan
    val notes = mutableListOf<String>()

    // R1a: overlap ±2 outgoing beats, clamped to planner-plausible rails.
    // Skipped on loop voices (lengths already armed into the vamp schedule).
    if (isBlend && outBeatSec != null && draws.fadeDeltaBeats != 0) {
        val before = humanized.fadeSeconds
        val nudged = (before + draws.fadeDeltaBeats * outBeatSec)
            .coerceIn(maxOf(2.0, before * 0.6), minOf(40.0, before * 1.4))
        if (nudged != before) {
            humanized = humanized.copy(
                fadeSeconds = nudged,
                transitionEnd = humanized.transitionStart + nudged,
                overlapSeconds = nudged,
            )
            notes += "fade ${"%.1f".format(before)}→${"%.1f".format(nudged)}"
        }
    }
    // R1b: rate push/pull, ±0.15 % — a DJ leaning on the pitch, inaudible as
    // pitch, felt as life. Inside the DJ ±2 % rails by two orders of magnitude.
    // Blends only: loop voices keep the planner's exact cue/rate geometry.
    if (isBlend && draws.rateDelta != 0.0 && humanized.incomingPlaybackRate > 0.0) {
        val before = humanized.incomingPlaybackRate
        humanized = humanized.copy(incomingPlaybackRate = before * (1.0 + draws.rateDelta))
        notes += "rate ×${"%.4f".format(1.0 + draws.rateDelta)}"
    }
    // R1c: cue to the nearest downbeat within ±1 beat. Blends only.
    if (isBlend && draws.cueSnap && outBeatSec != null && next != null) {
        val snapped = snapCueToGrid(humanized.incomingCueTime, next, outBeatSec)
        if (snapped != null && snapped != humanized.incomingCueTime) {
            val drift = snapped - humanized.incomingCueTime
            humanized = humanized.copy(incomingCueTime = snapped)
            notes += "cue ${"%+.2f".format(drift)}"
        }
    }
    // R1d: effect intensities — scale what the planner armed, never arm here
    // (arming is the wildcard's job).
    if (humanized.echoAmount > 0.0 && draws.echoScale != 1.0) {
        humanized = humanized.copy(echoAmount = (humanized.echoAmount * draws.echoScale).coerceIn(0.0, 1.0))
        notes += "echo ×${"%.2f".format(draws.echoScale)}"
    }
    if (humanized.reverbAmount > 0.0 && draws.reverbScale != 1.0) {
        humanized = humanized.copy(reverbAmount = (humanized.reverbAmount * draws.reverbScale).coerceIn(0.0, 1.0))
        notes += "verb ×${"%.2f".format(draws.reverbScale)}"
    }
    if (humanized.filterSweep != 0.0 && draws.sweepScale != 1.0) {
        humanized = humanized.copy(filterSweep = humanized.filterSweep * draws.sweepScale)
        notes += "sweep ×${"%.2f".format(draws.sweepScale)}"
    }
    if (isBlend && draws.swapDelta != 0.0) {
        humanized = humanized.copy(
            bassSwapFraction = (humanized.bassSwapFraction + draws.swapDelta).coerceIn(0.5, 0.9),
        )
    }

    // R2: the wildcard. Within-type arms only — same voice, one unplanned
    // punctuation. Blends only: never stack onto a loop or a spin.
    // The renderer's effect bookkeeping (blendsSinceEffect)
    // reads the armed fields off the render, so cooldowns follow for free.
    if (isBlend) when (draws.wildcard) {
        "throw" -> {
            humanized = humanized.copy(
                echoThrow = true,
                echoAmount = maxOf(humanized.echoAmount, 0.6),
            )
            notes += "wild=throw"
        }
        "brake" -> {
            humanized = humanized.copy(brake = true)
            notes += "wild=brake"
        }
        "backspin" -> {
            humanized = humanized.copy(backspin = true, brake = true, echoThrow = true)
            notes += "wild=backspin"
        }
    }
    if (notes.isEmpty()) return plan to null
    val seedNote = "seed=${st.mixes}"
    return humanized to "$seedNote ${notes.joinToString(" ")}"
}

/**
 * Pair-stable draws: roll once per pair, re-apply to every per-tick re-plan.
 * Null only when a previous roll already declined (no draws cached).
 */
private fun drawsFor(
    plan: TransitionPlan,
    out: TrackAnalysis?,
    next: TrackAnalysis?,
    outBeatSec: Double?,
    st: HumanizeState,
    pairKey: String,
): HumanDraws? {
    if (st.pairDrawsFor == pairKey) {
        return st.draws
    }
    st.mixes += 1
    if (st.wildcardCooldown > 0) st.wildcardCooldown -= 1
    val rolled = rollDraws(plan, out, next, outBeatSec, st, pairKey)
    st.pairDrawsFor = pairKey
    st.draws = rolled
    return rolled
}

private fun rollDraws(
    plan: TransitionPlan,
    out: TrackAnalysis?,
    next: TrackAnalysis?,
    outBeatSec: Double?,
    st: HumanizeState,
    pairKey: String,
): HumanDraws {
    val rng = Random(pairKey.hashCode() * 31 + st.mixes * 0x9E3779B9.toInt())
    return HumanDraws(
        fadeDeltaBeats = if (outBeatSec != null) rng.nextInt(-2, 3) else 0,
        rateDelta = (rng.nextDouble() - 0.5) * 0.003,
        cueSnap = rng.nextDouble() < 0.70,
        echoScale = 0.7 + rng.nextDouble() * 0.5,
        reverbScale = 0.7 + rng.nextDouble() * 0.5,
        sweepScale = 0.8 + rng.nextDouble() * 0.45,
        swapDelta = (rng.nextDouble() - 0.5) * 0.16,
        wildcard = rollWildcard(plan, out, next, outBeatSec, st, rng),
        // The hand: one decisive pull most plays, a re-grabbed stutter on
        // some — pair-stable like every other draw.
        spinGrabs = if (rng.nextDouble() < 0.30) rng.nextInt(2, 4) else 1,
    )
}

/**
 * Maybe arm one unplanned punctuation. Taste gates, in order: the planner's
 * own effect takes precedence (no stacking), a cooldown after the last one,
 * no repeats, grid present, room for the move, and never over a vocal clash
 * — punctuation over two singers is heckling, not DJing.
 */
private fun rollWildcard(
    plan: TransitionPlan,
    out: TrackAnalysis?,
    next: TrackAnalysis?,
    outBeatSec: Double?,
    st: HumanizeState,
    rng: Random,
): String? {
    if (plan.brake || plan.echoThrow || plan.backspin) return null
    if (st.wildcardCooldown > 0) return null
    if (outBeatSec == null || out == null || next == null) return null
    // Wildcard punctuation chance follows DJ intensity (LOW keeps 0.08).
    if (rng.nextDouble() >= AppSettings.djIntensity.value.wildcardChance) return null
    val fade = plan.fadeSeconds
    val candidates = mutableListOf("throw")
    if (fade >= 8.0) candidates += "brake"
    if (fade >= 6.0) candidates += "backspin"
    val clash = isVocalClash(
        vocalActivityBetween(out, plan.transitionStart, plan.transitionEnd),
        vocalActivityBetween(next, plan.incomingCueTime, plan.incomingCueTime + fade),
    )
    if (clash) return null
    val move = candidates[rng.nextInt(candidates.size)]
    if (move == st.lastWildcard) return null
    st.lastWildcard = move
    st.wildcardCooldown = 2
    return move
}

/**
 * Nearest incoming downbeat within ±1 grid beat of [cue], or null when the
 * grid gives nothing there. Smallest possible tasteful move: the cue lands
 * on a phrase edge instead of a beat off it.
 */
fun snapCueToGrid(cue: Double, next: TrackAnalysis, beatSec: Double): Double? {
    if (!cue.isFinite() || next.downbeats.isEmpty()) return null
    var best: Double? = null
    var bestDist = beatSec
    for (downbeat in next.downbeats) {
        if (!downbeat.isFinite()) continue
        val dist = abs(downbeat - cue)
        if (dist < bestDist) {
            bestDist = dist
            best = downbeat
        }
    }
    return best
}
