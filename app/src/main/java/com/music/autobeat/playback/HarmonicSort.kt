package com.music.autobeat.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.music.autobeat.data.TrackLog
import com.music.autobeat.data.settings.AppSettings
import com.music.autobeat.playback.smart.TrackAnalysis
import com.music.autobeat.playback.smart.TrackAnalyzer
import com.music.autobeat.playback.smart.camelotLabel
import com.music.autobeat.playback.smart.camelotOf
import com.music.autobeat.playback.smart.findBestCandidate
import com.music.autobeat.playback.smart.meanEnergy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.log2

/**
 * Harmonic Sort as an edit to the queue, not a playback mode — the same shape
 * as [QueueShuffle], but the order is earned by measurement rather than drawn
 * by chance.
 *
 * Turning it on snapshots the queue, measures the key and tempo of the
 * twenty tracks still to come, and keeps measuring as the queue moves: a
 * finished track sliding out or a refill sliding in just becomes the next
 * pass, so the twenty ahead stay measured — user inserts, autoplay and
 * infinity refills included. Every answer rearranges the window into the
 * chain where each handover scores highest — the same pair scorer the DJ
 * planner trusts ([findBestCandidate]), run greedily forward from the track
 * that is playing. Turning it off puts the queue back the way it was found.
 *
 * Since the vibe picker, the greedy is steered by a set arc as well as the
 * handover: each slot maximizes
 * `pair + route − tempoDrift + vocal − λ·|dropEnergy − target|`, where the
 * target comes from the selected [Vibe]. Same scorer, same real-time jumps —
 * but the chain now walks the wheel, climbs toward its own median tempo,
 * peaks on the drop instead of the outro, and breathes between vocals.
 *
 * The measuring is the hard part, and it is deliberately sequential. The
 * analyzer drains through one or two single-threaded FIFO lanes, each decode
 * costs seconds and tens of megabytes, and the disk cache holds about thirty
 * worst-case tracks — firing the whole scope at once would queue minutes of
 * work, pin the CPU, and thrash the cache into evicting the very bytes being
 * measured. So one track at a time: pull its bytes, ask for its analysis,
 * wait for the answer (or the timeout) — and every answer re-sorts and
 * re-applies immediately, so the queue jumps track by track in real time.
 * Tracks already measured —
 * this session or a previous one, via the analysis store — cost nothing.
 *
 * An AutoPlay track that has been measured earns its place in the chain:
 * the sort runs over one combined section and the flag is cleared on
 * measured AutoPlay tracks, so they stand above the heading with the queue.
 * Unmeasured AutoPlay tracks stay flagged below it. (A shuffle keeps the
 * sections apart; a sort is a reason to cut in front.)
 *
 * Not persisted across restarts: the pre-sort order only exists in memory, so
 * there is nothing truthful to restore to after one. The sorted order itself
 * persists naturally, as the queue snapshot the service already writes.
 */
object HarmonicSort {

    /** How far ahead of the playing track measuring and sorting reach. */
    const val MAX_SORT_AHEAD = 20

    /** Per-track wait for an analysis to land, aligned with the analyzer's own stuck watchdog. */
    private const val TRACK_TIMEOUT_MS = 90_000L
    private const val POLL_MS = 400L
    /**
     * Parked ids earn retries, not a life sentence: a deep online track's
     * single 90 s attempt usually dies on a cold resolve + full download
     * behind the current track's own decode — not on an unmeasurable file.
     * Up to 3 attempts, and only once the track is within 6 slots of the
     * front (bytes warm, lane likelier free). Locals still land first try.
     */
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_WINDOW = 6

    /** How far the toggle has got through the scope, or null while not measuring. */
    data class Progress(val done: Int, val total: Int)

    /**
     * The set arc: where each position in the sorted scope should sit
     * energy-wise. A greedy pair scorer alone makes every handover pretty
     * but leaves the set wandering — no build, no peak, no landing. The vibe
     * scores each candidate against where the arc wants that slot, so the
     * chain tells a story instead of a series of nice accidents.
     */
    enum class Vibe {
        WARM_UP,
        PEAK,
        ARC,
        COOL_DOWN,
        LATE_NIGHT,
    }

    /**
     * Target normalized energy (0..1) at scope fraction [t] for [vibe].
     * Curves, not constants: a peak with no breath is a wall, a warm-up
     * with no patience is just quiet.
     */
    fun targetEnergy(vibe: Vibe, t: Double): Double {
        val clamped = t.coerceIn(0.0, 1.0)
        return when (vibe) {
            Vibe.WARM_UP -> 0.25 + 0.65 * clamped
            // Peak breathes: 0.80 at the edges, 0.98 at the apex. The old
            // flat 0.88±0.06 line produced near-identical orders unless the
            // scope was bimodal — a peak with no breath is a wall.
            Vibe.PEAK -> 0.80 + 0.18 * kotlin.math.sin(clamped * Math.PI)
            Vibe.ARC -> if (clamped < 0.65) {
                0.30 + 0.70 * (clamped / 0.65)
            } else {
                1.0 - 0.70 * ((clamped - 0.65) / 0.35)
            }
            Vibe.COOL_DOWN -> 0.75 - 0.50 * clamped
            // Late night descends audibly (0.38 → 0.24) with a small breathing
            // wave, instead of hovering flat at 0.30 — the old line could not
            // steer at all.
            Vibe.LATE_NIGHT -> 0.38 - 0.14 * clamped + 0.04 * kotlin.math.sin(clamped * 2 * Math.PI)
        }.coerceIn(0.0, 1.0)
    }

    /**
     * How hard the arc pulls against the pair score. Pair scores and energy
     * deviations both live ~0..1, so 0.6 lets a great handover beat the arc
     * but not ignore it.
     */
    const val VIBE_LAMBDA = 0.6

    /**
     * Key-route memory: a greedy that only sees the last handover wanders the
     * wheel — up a fifth, down a fifth, mode-flapping back and forth. Real sets
     * walk: keep climbing while climbing, resolve while resolving. The terms
     * are small on purpose; route steers between near-equal handovers, it
     * never overrules a genuinely better blend.
     */
    private const val ROUTE_CONTINUE_BONUS = 0.08
    private const val ROUTE_REVERSAL_PENALTY = 0.10
    private const val ROUTE_FLAP_PENALTY = 0.06

    /**
     * Energy-boost: a deliberate +2 wheel jump on the same ring (8A -> 10A)
     * reads as a lift, not a clash — but only timed at the peak and only when
     * the tempo already locks. Anywhere else, or with a loose tempo fit, the
     * far key keeps its 0.0 and the sort walks past it.
     */
    private const val BOOST_KEY_SCORE = 0.55
    private const val BOOST_MIN_BPM_FIT = 0.7
    private const val BOOST_KEY_WEIGHT = 0.30

    /**
     * Tempo trajectory: the set should drift toward its own median, not
     * sawtooth 128 -> 100 -> 128 through ratio rescues. Penalty is in octaves
     * so a 3 BPM nudge costs ~0.01 and a fifth-jump costs ~0.15.
     */
    private const val TEMPO_TRAJECTORY_WEIGHT = 0.25

    /**
     * Palate cleanser: two vocal choruses back to back tire the ear faster
     * than any key clash. Punish stacking, reward an instrumental breather —
     * gently, as a nudge between near-equal handovers.
     */
    private const val VOCAL_STACK_PENALTY = 0.12
    private const val VOCAL_CLEAN_BONUS = 0.06
    private const val VOCAL_HEAVY = 0.5
    private const val VOCAL_SPARSE = 0.2

    /** 30-second sliding window for the drop-energy read. */
    private const val DROP_WINDOW_SECONDS = 30.0
    /** Ignored head/tail share of the curve: intro fades and outro tails lie. */
    private const val DROP_EDGE_TRIM = 0.10

    private val _active = MutableStateFlow(false)

    /** Whether the queue is currently held in harmonic order. */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Media ids in their pre-sort order. Empty while the sort is off. */
    private var original: List<String> = emptyList()
    /**
     * The measured scope, kept so a vibe change can re-sort without
     * re-measuring. Only grows while the sort is on: every live 20-ahead
     * window folds in, so refills stay sortable without a second pass over
     * the tracks already placed.
     */
    private var scopeIds: List<String> = emptyList()
    /** Attempt counts per id this activation: unmeasurable ones park here so the drain never spins on them. */
    private val attempted = mutableMapOf<String, Int>()
    private var worker: Job? = null
    private var generation = 0
    /**
     * Trailing-edge debounce for re-applying the sort: a burst of landings
     * (store hits arrive in milliseconds) folds into one queue edit instead
     * of one PLAYLIST_CHANGED per track, which used to churn the analyzer's
     * next slot under its own feet. Lonely landings sail straight through.
     */
    private const val SORT_APPLY_DEBOUNCE_MS = 2_000L
    private var lastSortApplyMs = 0L
    private var sortApplyPending = false

    /** What the sort borrows from the service: its scope, analyzer, and disk cache. */
    class Deps(
        val scope: CoroutineScope,
        val analyzer: TrackAnalyzer,
        val cache: AudioCache,
    )

    /**
     * @param current re-reads the live session player on every step: the worker
     * outlives track changes, and a crossfade handoff can retire the instance
     * it started from. Defaults to the player handed in, which is what the
     * synchronous callers already hold.
     */
    fun toggle(player: Player, deps: Deps, current: () -> Player? = { player }) {
        TrackLog.d(
            "Autobeat",
            "harmonic toggle: active=${_active.value} shuffle=${QueueShuffle.enabled.value} " +
                "count=${player.mediaItemCount} current=${player.currentMediaItemIndex}",
            null,
        )
        if (_active.value) {
            cancelAndRestore(player)
            return
        }
        // Mutually exclusive with shuffle: sorting a shuffled order would stack
        // intent on top of randomness, and shuffling a sorted one spends the
        // measuring just done. Whichever is on stands down first. Guarded: a
        // throw here used to abort the whole toggle with nothing on screen
        // saying so.
        if (QueueShuffle.enabled.value) {
            runCatching { QueueShuffle.toggle(player) }
                .onFailure { TrackLog.w("Autobeat", "harmonic shuffle stand-down failed: ${it.message}", it, null) }
        }
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) {
            TrackLog.w(
                "Autobeat",
                "harmonic toggle: nothing ahead (from=$from count=${player.mediaItemCount})",
                null,
            )
            return
        }
        original = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        val scopeIds = original.drop(from).take(MAX_SORT_AHEAD)
        if (scopeIds.isEmpty()) {
            original = emptyList()
            return
        }
        this.scopeIds = scopeIds
        attempted.clear()
        lastSortApplyMs = 0L
        sortApplyPending = false
        _active.value = true
        _progress.value = Progress(0, scopeIds.size)
        generation++
        startWorker(deps, current)
    }

    /**
     * Wakes the measuring worker when the queue grows under an active sort —
     * a user insert, an autoplay or infinity refill, a radio handoff. No-op
     * while the sort is off or the worker is already draining: measuring is
     * sequential, so there is ever exactly one.
     */
    fun topUp(player: Player, deps: Deps, current: () -> Player? = { player }) {
        if (!_active.value) return
        if (worker?.isActive == true) return
        startWorker(deps, current)
    }

    private fun startWorker(deps: Deps, current: () -> Player?) {
        val myGeneration = generation
        worker = deps.scope.launch {
            TrackLog.d("Autobeat", "harmonic worker started", null)
            drainLoop(deps, current, myGeneration)
        }
    }

    /**
     * Rolling 20-ahead: every pass re-reads the live upcoming window,
     * measures the first track in it with no usable analysis, re-sorts on
     * every landing, and parks — leaving the count on screen — when the
     * whole window reads usable. [topUp] relaunches on every queue change,
     * so a finished track sliding out or a refill sliding in just becomes
     * the next pass. The terminal count stays published until the sort
     * stands down: the pill reads "20/20", not a bare icon.
     */
    private suspend fun drainLoop(deps: Deps, current: () -> Player?, myGeneration: Int) {
        while (true) {
            if (myGeneration != generation) return
            val live = current() ?: return
            extendScope(live)
            publishProgress(live, deps)
            val next = nextNeedingMeasure(live, deps) ?: run {
                // Flush a debounced apply before parking: otherwise the last
                // landing's order never reaches the queue.
                if (sortApplyPending) maybeApplySort(live, deps, force = true)
                TrackLog.d("Autobeat", "harmonic worker parked, window measured", null)
                return
            }
            if (myGeneration != generation) return
            // Guarded per track: one unmeasurable id must not take the
            // others down with it, silently or otherwise.
            runCatching { ensureAnalysed(current(), deps, next) }
                .onFailure {
                    if (it is CancellationException) throw it
                    TrackLog.w("Autobeat", "harmonic track $next failed: ${it.message}", it, null)
                }
            attempted[next] = (attempted[next] ?: 0) + 1
            if (myGeneration != generation) return
            val usable = deps.analyzer.analysisFor(next).isUsable
            TrackLog.d("Autobeat", "harmonic measured $next usable=$usable", null)
            // Real-time but debounced: a burst of store-hit landings folds
            // into one apply instead of churning PLAYLIST_CHANGED (and the
            // analyzer's next slot) per track. Lonely landings sail straight
            // through — the debounce only bites inside the window.
            current()?.let { l -> maybeApplySort(l, deps) }
            if (myGeneration != generation) return
            publishProgress(current() ?: live, deps)
        }
    }

    /** Live upcoming ids, oldest first. */
    private fun upcomingIds(player: Player): List<String> {
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) return emptyList()
        return List(player.mediaItemCount - from) { player.getMediaItemAt(from + it).mediaId }
    }

    /**
     * Folds the live 20-ahead window into the measured scope (never shrinks
     * the window itself). Played ids are pruned: the scope only ever steers
     * live tracks, and without pruning a long session accumulates every id it
     * ever saw — including ones repeat-all rotated back to the end, which
     * would then read as already-placed.
     */
    private fun extendScope(player: Player) {
        val window = upcomingIds(player).take(MAX_SORT_AHEAD)
        if (window.isEmpty()) return
        val live = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }.toSet()
        scopeIds = (scopeIds.filter { it in live } + window).distinct()
    }

    /**
     * First window track with no usable analysis: never-attempted ids first,
     * then the frontmost id with attempts left once it is within the retry
     * window. The frontier advances instead of freezing at track2.
     */
    private fun nextNeedingMeasure(player: Player, deps: Deps): String? {
        val window = upcomingIds(player).take(MAX_SORT_AHEAD)
        window.firstOrNull {
            (attempted[it] ?: 0) == 0 && !deps.analyzer.analysisFor(it).isUsable
        }?.let { return it }
        return window.take(RETRY_WINDOW).firstOrNull {
            val n = attempted[it] ?: 0
            n in 1 until MAX_ATTEMPTS && !deps.analyzer.analysisFor(it).isUsable
        }
    }

    /** Publishes usable-vs-window so the pill always reads a count, never a bare icon. */
    private fun publishProgress(player: Player, deps: Deps) {
        val window = upcomingIds(player).take(MAX_SORT_AHEAD)
        if (window.isEmpty()) return
        _progress.value = Progress(window.count { deps.analyzer.analysisFor(it).isUsable }, window.size)
    }

    /**
     * Stands the sort down: stops any measuring in flight and puts the tracks
     * still to come back into the order they were queued in. Also what enabling
     * shuffle calls, so the two modes never stack.
     */
    fun cancelAndRestore(player: Player) {
        generation++
        worker?.cancel()
        worker = null
        TrackLog.d("Autobeat", "harmonic stand-down: restoring pre-sort order", null)
        if (_active.value) restore(player)
        _active.value = false
        _progress.value = null
    }

    /**
     * Re-sorts the live scope under the current vibe without re-measuring —
     * what a vibe change calls. No-op while the sort is off or the scope
     * has drained to one slot.
     */
    fun resort(player: Player, deps: Deps) {
        if (!_active.value || scopeIds.isEmpty()) return
        runCatching { sortAndApply(player, deps, scopeIds) }
            .onFailure { TrackLog.w("Autobeat", "harmonic resort failed: ${it.message}", it, null) }
    }

    /**
     * Gets [id] measured, or accepts that it cannot be. A usable recorded
     * result — this session's or a previous one's — short-circuits everything.
     * Otherwise its bytes are pulled first (measuring an uncached track without
     * them is what the head-fetch fallback is for, and a sort wants whole-track
     * answers), the analysis is requested, and the answer is awaited: usable
     * lands it in the sort, a recorded failure or the timeout leaves it to the
     * unsorted tail.
     */
    private suspend fun ensureAnalysed(player: Player?, deps: Deps, id: String) {
        if (deps.analyzer.analysisFor(id).isUsable) return
        val host = player ?: return
        val liveIndex = host.indexOfId(id) ?: return
        val uri = host.getMediaItemAt(liveIndex).localConfiguration?.uri ?: return
        // Warm the two behind it too: the worker measures sequentially, so
        // by the time it reaches them their bytes are already on disk
        // instead of each starting its resolve cold inside its own 90 s.
        val follow = (1..2).mapNotNull { off ->
            val i = liveIndex + off
            if (i < host.mediaItemCount) host.getMediaItemAt(i).mediaId else null
        }
        // In-window pulls only: the service owns current+next bytes through
        // its own pulls, and the old unconditional pull evicted them (and was
        // evicted in turn), so nothing ever completed. Deep tracks analyze
        // off their heads instead of starving the front; a cold priorityIds
        // (service hasn't published yet) still pulls the one id, never the
        // followers.
        val priority = deps.analyzer.priorityIds
        val wanted = (listOf(id) + follow).filter { it in priority }
        deps.cache.forceFullPull(if (wanted.isNotEmpty()) wanted else listOf(id))
        deps.analyzer.request(id, uri, durationSeconds(host, liveIndex))
        try {
            withTimeout(TRACK_TIMEOUT_MS) {
                while (true) {
                    val recorded = deps.analyzer.analysisFor(id)
                    // Usable ends the wait; a recorded failure with nothing in
                    // flight ends it too — the analyzer writes those precisely
                    // so the track stops being retried.
                    if (recorded.isUsable) return@withTimeout
                    if (deps.analyzer.isAnalysed(id) && !deps.analyzer.isAnalysing(id)) return@withTimeout
                    delay(POLL_MS)
                }
            }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w("Autobeat", "harmonic sort gave up waiting on $id", null)
        }
    }

    /**
     * What the crowd actually hears: the loudest 30-second window, not the
     * whole-track mean. A nuclear drop with a long ambient outro used to read
     * as "low energy" because the tail diluted the mean; the window hears the
     * drop. Trims the outer 10% so intro fades and end tails cannot win.
     * Falls back to the mean on short or degenerate curves.
     */
    private fun dropEnergy(analysis: TrackAnalysis): Double? {
        val pts = analysis.energyCurve.filter {
            it.time.isFinite() && it.energy.isFinite() && it.energy >= 0
        }
        if (pts.size < 2) return meanEnergy(analysis)
        val span = pts.last().time - pts.first().time
        if (!span.isFinite() || span <= 0) return meanEnergy(analysis)
        val lo = pts.first().time + span * DROP_EDGE_TRIM
        val hi = pts.last().time - span * DROP_EDGE_TRIM
        if (hi <= lo) return meanEnergy(analysis)
        var best = Double.NEGATIVE_INFINITY
        for (p in pts) {
            if (p.time < lo || p.time > hi) continue
            var sum = 0.0
            var n = 0
            for (q in pts) {
                if (q.time >= p.time && q.time <= p.time + DROP_WINDOW_SECONDS) {
                    sum += q.energy
                    n++
                }
            }
            if (n > 0 && sum / n > best) best = sum / n
        }
        return if (best.isFinite()) best else meanEnergy(analysis)
    }

    /**
     * Absolute energy anchor from the master descriptors: a crushed loud
     * master reads hotter than a quiet dynamic one even when their P85 curve
     * shapes match. Scope-relative drop energy alone double-relativizes (per
     * track P85, then per scope min-max) and destroys the absolute meaning of
     * the 0..1 vibe targets. Null when unmeasured (-70 LUFS sentinel).
     */
    private fun absoluteEnergy(analysis: TrackAnalysis): Double? {
        if (analysis.loudnessLufs <= -69.0) return null
        val loud = ((analysis.loudnessLufs + 30.0) / 25.0).coerceIn(0.0, 1.0)
        val dyn = if (analysis.dynamicRangeDb <= 0.0) 0.5
            else 1.0 - (analysis.dynamicRangeDb / 20.0).coerceIn(0.0, 1.0)
        return (0.6 * loud + 0.4 * dyn).coerceIn(0.0, 1.0)
    }

    /**
     * Share of the track under voice: mean of the vocal-activity mask. Null
     * when the analyzer measured no mask, which reads as no opinion.
     */
    private fun vocalDensity(analysis: TrackAnalysis): Double? {
        val mask = analysis.vocalActivityMask.filter { it.isFinite() && it >= 0 }
        if (mask.isEmpty()) return null
        return mask.sum() / mask.size
    }

    /** Clockwise wheel distance 0..11 on the same ring, or null when either key is unparseable or cross-ring. */
    private fun wheelDelta(fromKey: String, toKey: String): Int? {
        val (aNum, aMinor) = camelotOf(fromKey) ?: return null
        val (bNum, bMinor) = camelotOf(toKey) ?: return null
        if (aMinor != bMinor) return null
        return ((bNum - aNum) % 12 + 12) % 12
    }

    /**
     * Route term from the two-step key history: keep walking the wheel in the
     * direction already established, punish an immediate reversal, punish
     * A -> B -> A mode-flapping on one number. Only ±1 steps participate;
     * anything farther has no route opinion (the boost rule owns +2).
     */
    private fun routeTerm(prevKey: String?, cursorKey: String, candKey: String): Double {
        if (prevKey.isNullOrBlank()) return 0.0
        val stepOf: (Int) -> Int? = { delta ->
            when (delta) {
                0 -> 0
                1 -> 1
                11 -> -1
                else -> null
            }
        }
        var term = 0.0
        val d1 = wheelDelta(prevKey, cursorKey)?.let(stepOf)
        val d2 = wheelDelta(cursorKey, candKey)?.let(stepOf)
        if (d1 != null && d1 != 0 && d2 == d1) term += ROUTE_CONTINUE_BONUS
        if (d1 != null && d1 != 0 && d2 != null && d2 == -d1) term -= ROUTE_REVERSAL_PENALTY
        val prev = camelotOf(prevKey)
        val cur = camelotOf(cursorKey)
        val nxt = camelotOf(candKey)
        if (prev != null && cur != null && nxt != null &&
            prev.first == cur.first && cur.first == nxt.first &&
            prev.second == nxt.second && prev.second != cur.second
        ) term -= ROUTE_FLAP_PENALTY
        return term
    }

    /** Deliberate +2 clockwise jump on the same ring: the energy-boost move. */
    private fun isEnergyBoost(cursorKey: String, candKey: String): Boolean =
        wheelDelta(cursorKey, candKey) == 2

    /** The boost only fires where a lift belongs: PEAK everywhere, ARC near its top. */
    private fun isPeakSlot(vibe: Vibe, slotFraction: Double): Boolean =
        vibe == Vibe.PEAK || (vibe == Vibe.ARC && slotFraction in 0.5..0.8)

    /**
     * Trailing-edge debounced [sortAndApply]: inside the window the apply is
     * remembered, not run, and the next call past the window applies once for
     * the whole burst. [force] flushes (worker parking).
     */
    private fun maybeApplySort(player: Player, deps: Deps, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSortApplyMs < SORT_APPLY_DEBOUNCE_MS) {
            sortApplyPending = true
            return
        }
        lastSortApplyMs = now
        sortApplyPending = false
        runCatching { sortAndApply(player, deps, scopeIds) }
            .onFailure { TrackLog.w("Autobeat", "harmonic apply failed: ${it.message}", it, null) }
    }

    /**
     * Rearranges the tracks still to come into the highest-scoring chain, in
     * one edit — see [QueueShuffle.applyOrder] for why one edit and why a
     * permutation.
     *
     * Only tracks from the measured scope move, and only among the slots they
     * already hold: everything beyond the scope, anything queued or dragged
     * since, and anything unmeasurable stays exactly where it is. User and
     * AutoPlay tracks sort as one section; a measured AutoPlay track has its
     * flag cleared so the heading drops below it, while unmeasured
     * suggestions keep theirs and stay under the heading.
     *
     * The flag clear is one-way for the session: [restore] puts the ids back
     * but does not re-flag, so a promoted track keeps standing with the
     * queue after the sort is toggled off.
     */
    private fun sortAndApply(player: Player, deps: Deps, scopeIds: List<String>) {
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) return
        val upcoming = List(player.mediaItemCount - from) { player.getMediaItemAt(from + it) }
        val scope = scopeIds.toSet()
        val sortableSlots = upcoming.indices.filter { upcoming[it].mediaId in scope }
        if (sortableSlots.size <= 1) {
            TrackLog.d("Autobeat", "harmonic apply: only ${sortableSlots.size} scope track(s) left, keeping order", null)
            return
        }
        val analyses = sortableSlots.associate { upcoming[it].mediaId to deps.analyzer.analysisFor(upcoming[it].mediaId) }
        val anchor = player.currentMediaItem?.let { analyses[it.mediaId] ?: deps.analyzer.analysisFor(it.mediaId) }
            ?.takeIf { it.isUsable }
        val vibe = AppSettings.harmonicVibe.value
        // Arc targets need comparable energies: min-max normalize the scope's
        // drop-window energies to 0..1. A flat scope (or none measured) reads
        // 0.5 everywhere, which degrades exactly to the old pair-only greedy.
        val rawEnergies = analyses.mapValues { (_, analysis) -> dropEnergy(analysis) }
        val finite = rawEnergies.values.filterNotNull().filter { it.isFinite() }
        val eMin = finite.minOrNull() ?: 0.0
        val eMax = finite.maxOrNull() ?: 0.0
        // Absolute anchor: 70% scope-relative shape, 30% master truth, so a
        // loud crushed track still reads hotter than a quiet dynamic one in
        // the same scope. Unmeasured masters fall back to relative only.
        val absolute = analyses.mapValues { (_, analysis) -> absoluteEnergy(analysis) }
        val energies = rawEnergies.mapValues { (id, raw) ->
            val rel = if (raw == null || !raw.isFinite() || eMax <= eMin) 0.5
                else ((raw - eMin) / (eMax - eMin)).coerceIn(0.0, 1.0)
            val abs = absolute[id]
            if (abs == null) rel else (0.7 * rel + 0.3 * abs).coerceIn(0.0, 1.0)
        }
        // Tempo trajectory: the set drifts from the anchor toward the scope
        // median, so a smooth climb wins and a sawtooth pays per octave.
        val anchorBpm = anchor?.bpm?.takeIf { it > 0 } ?: 0.0
        val scopeBpms = analyses.values.mapNotNull { it.bpm.takeIf { b -> b > 0 } }
        // One combined section: a measured AutoPlay track belongs wherever
        // the chain puts it, not fenced below a heading.
        val sorted = sortSection(
            sortableSlots.map { upcoming[it] }, analyses, energies, anchor, vibe, anchorBpm, scopeBpms,
        )
        // Promote what the sort just measured: clear the flag on AutoPlay
        // tracks with a usable analysis so the heading drops below them.
        // Unmeasured suggestions keep the flag and stay under the heading.
        // Session-player only: items read back through a controller have no
        // playback URI left, so a controller-side replace would strip them
        // (see QueueShuffle.applyOrder). Order-only fallback there.
        if (player !is MediaController) {
            sortableSlots.forEach { slot ->
                val item = upcoming[slot]
                if (item.fromAutoplay && analyses[item.mediaId]?.isUsable == true) {
                    player.replaceMediaItem(from + slot, item.withAutoplayCleared())
                }
            }
        }
        // Back into slots: each sorted track takes the slot its predecessor in
        // the sorted order vacated, so non-scope tracks never shift.
        val positions = HashMap<String, ArrayDeque<Int>>(sortableSlots.size)
        sortableSlots.forEach { positions.getOrPut(upcoming[it].mediaId) { ArrayDeque() }.addLast(it) }
        val placed = sorted.map { positions[it.mediaId]?.removeFirstOrNull() ?: return }
        val order = upcoming.indices.map { slot ->
            val sortableAt = sortableSlots.indexOf(slot)
            if (sortableAt < 0) slot else placed[sortableAt]
        }
        TrackLog.d("Autobeat", "harmonic sort placed ${sorted.size} of ${upcoming.size} upcoming", null)
        QueueShuffle.applyFromSession(player, from, order)
    }

    /**
     * Same track, no longer AutoPlay's: drops [EXTRA_FROM_AUTOPLAY] from the
     * extras while keeping id, URI and everything else, so the queue heading
     * ([autoplaySectionStart]) falls below it. Built from the session's own
     * item, never a controller's — see the call site.
     */
    private fun MediaItem.withAutoplayCleared(): MediaItem {
        val extras = (mediaMetadata.extras?.deepCopy() ?: Bundle()).apply {
            remove(EXTRA_FROM_AUTOPLAY)
        }
        val metadata = MediaMetadata.Builder(mediaMetadata).setExtras(extras).build()
        return buildUpon().setMediaMetadata(metadata).build()
    }

    /**
     * The highest-scoring chain through [tracks], greedy forward from [anchor] —
     * but scored like a DJ thinks, not just like a blend sounds. At each step
     * the winner maximizes
     * `pair + route − tempoDrift + vocal − λ·|energy − target|`:
     * blendability first, then keep walking the wheel the way it was going
     * (with a deliberate +2 lift allowed at the peak), drift the tempo toward
     * the set's own median instead of sawtoothing, and don't stack two vocal
     * choruses. The slot fraction runs 0..1 across this section, so the curve
     * lands where the listener is, not where measuring happened to finish.
     * Unmeasurable tracks keep their relative order at the end, as before.
     */
    private fun sortSection(
        tracks: List<MediaItem>,
        analyses: Map<String, TrackAnalysis>,
        energies: Map<String, Double>,
        anchor: TrackAnalysis?,
        vibe: Vibe,
        anchorBpm: Double,
        scopeBpms: List<Double>,
    ): List<MediaItem> {
        val (usable, failed) = tracks.partition { analyses[it.mediaId]?.isUsable == true }
        val ordered = ArrayList<MediaItem>(usable.size)
        val remaining = usable.toMutableList()
        val medianBpm = scopeBpms.sorted().let { sorted ->
            if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
        }
        var cursor = anchor
        var prev: TrackAnalysis? = null
        while (remaining.isNotEmpty()) {
            val slotFraction = if (usable.size <= 1) 0.0
                else ordered.size.toDouble() / (usable.size - 1).toDouble()
            val target = targetEnergy(vibe, slotFraction)
            val current = cursor
            val next = if (current == null) {
                // No anchor (nothing usable playing): open on the track
                // closest to where the arc starts.
                var best = 0
                var bestDistance = Double.POSITIVE_INFINITY
                remaining.forEachIndexed { index, item ->
                    val distance = abs((energies[item.mediaId] ?: 0.5) - target)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = index
                    }
                }
                remaining.removeAt(best)
            } else {
                var best = 0
                var bestScore = Double.NEGATIVE_INFINITY
                var bestBoost: String? = null
                // Tempo trajectory for this slot: from the anchor toward the
                // scope median. No anchor, no median, no opinion.
                val slotTempo = if (anchorBpm > 0 && medianBpm > 0) {
                    anchorBpm + (medianBpm - anchorBpm) * slotFraction
                } else 0.0
                remaining.forEachIndexed { index, item ->
                    val candidate = analyses[item.mediaId] ?: return@forEachIndexed
                    val pair = findBestCandidate(current, candidate)
                        ?: return@forEachIndexed
                    var score = pair.candidateScore
                    var boost: String? = null
                    // Energy-boost: a far key reads 0.0, but a deliberate +2 at
                    // the peak with a locked tempo is a lift, not a clash.
                    val bpmFit = (1.0 - pair.diff / pair.deviationCap).coerceIn(0.0, 1.0)
                    if (isEnergyBoost(current.key, candidate.key) &&
                        isPeakSlot(vibe, slotFraction) && bpmFit >= BOOST_MIN_BPM_FIT &&
                        pair.keyFitScore < BOOST_KEY_SCORE
                    ) {
                        score += (BOOST_KEY_SCORE - pair.keyFitScore) * BOOST_KEY_WEIGHT
                        boost = "${camelotLabel(current.key) ?: "?"}->${camelotLabel(candidate.key) ?: "?"}"
                    }
                    score += routeTerm(prev?.key, current.key, candidate.key)
                    val candBpm = candidate.bpm
                    if (slotTempo > 0 && candBpm > 0) {
                        score -= TEMPO_TRAJECTORY_WEIGHT * abs(log2(candBpm / slotTempo))
                    }
                    val cursorVocal = vocalDensity(current)
                    val candVocal = vocalDensity(candidate)
                    if (cursorVocal != null && candVocal != null && cursorVocal > VOCAL_HEAVY) {
                        score += if (candVocal > VOCAL_HEAVY) -VOCAL_STACK_PENALTY
                        else if (candVocal < VOCAL_SPARSE) VOCAL_CLEAN_BONUS
                        else 0.0
                    }
                    val arcPenalty = VIBE_LAMBDA * abs((energies[item.mediaId] ?: 0.5) - target)
                    score -= arcPenalty
                    if (score > bestScore) {
                        bestScore = score
                        best = index
                        bestBoost = boost
                    }
                }
                val picked = remaining.removeAt(best)
                if (bestBoost != null) {
                    TrackLog.d("Autobeat", "harmonic route: energy-boost $bestBoost @${(slotFraction * 100).toInt()}% peak", null)
                }
                picked
            }
            ordered += next
            prev = cursor
            cursor = analyses[next.mediaId]?.takeIf { it.isUsable }
        }
        return ordered + failed
    }

    /** Puts the tracks still to come back into the order they were queued in. */
    private fun restore(player: Player) {
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) {
            val upcoming = List(player.mediaItemCount - from) { player.getMediaItemAt(from + it) }
            val order = QueueShuffle.restoreOrder(upcoming.map { it.mediaId }, original)
            val partitioned = order.filterNot { upcoming[it].fromAutoplay } +
                order.filter { upcoming[it].fromAutoplay }
            QueueShuffle.applyFromSession(player, from, partitioned)
        }
        original = emptyList()
        scopeIds = emptyList()
        attempted.clear()
    }

    private fun Player.indexOfId(id: String): Int? =
        (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == id }

    /**
     * The runtime the queue row carried, in seconds, or 0 when the item does
     * not state one — mirroring the crossfade's own duration read, since the
     * analysis request wants the same number it gets there.
     */
    private fun durationSeconds(player: Player, index: Int): Double {
        val timeline = player.currentTimeline
        if (!timeline.isEmpty) {
            timeline.getWindow(index, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { return it / 1000.0 }
        }
        val uri = player.getMediaItemAt(index).localConfiguration?.uri
        val seconds = uri?.let { runCatching { it.getQueryParameter("d") }.getOrNull()?.toLongOrNull() } ?: 0L
        return if (seconds > 0) seconds.toDouble() else 0.0
    }
}
