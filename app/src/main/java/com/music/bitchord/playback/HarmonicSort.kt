package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackAnalyzer
import com.music.bitchord.playback.smart.findBestCandidate
import com.music.bitchord.playback.smart.meanEnergy
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
 * handover: each slot maximizes `pairScore − λ·|energy − target|`, where the
 * target comes from the selected [Vibe]. Same scorer, same real-time jumps —
 * but the chain now climbs, peaks, or cools down on purpose.
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
 * AutoPlay's tracks are sorted among themselves and stay below the ones the
 * user queued, for the same reason they do under a shuffle: a sort is not a
 * reason for a mix to start cutting in front of the album.
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
            Vibe.PEAK -> 0.88 + 0.06 * kotlin.math.sin(clamped * Math.PI)
            Vibe.ARC -> if (clamped < 0.65) {
                0.30 + 0.70 * (clamped / 0.65)
            } else {
                1.0 - 0.70 * ((clamped - 0.65) / 0.35)
            }
            Vibe.COOL_DOWN -> 0.75 - 0.50 * clamped
            Vibe.LATE_NIGHT -> 0.30 + 0.05 * kotlin.math.sin(clamped * 2 * Math.PI)
        }.coerceIn(0.0, 1.0)
    }

    /**
     * How hard the arc pulls against the pair score. Pair scores and energy
     * deviations both live ~0..1, so 0.6 lets a great handover beat the arc
     * but not ignore it.
     */
    const val VIBE_LAMBDA = 0.6

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
    /** Ids this activation already attempted: unmeasurable ones park here so the drain never spins on them. */
    private val attempted = mutableSetOf<String>()
    private var worker: Job? = null
    private var generation = 0

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
            "BitChord",
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
                .onFailure { TrackLog.w("BitChord", "harmonic shuffle stand-down failed: ${it.message}", it, null) }
        }
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) {
            TrackLog.w(
                "BitChord",
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
            TrackLog.d("BitChord", "harmonic worker started", null)
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
                TrackLog.d("BitChord", "harmonic worker parked, window measured", null)
                return
            }
            if (myGeneration != generation) return
            // Guarded per track: one unmeasurable id must not take the
            // others down with it, silently or otherwise.
            runCatching { ensureAnalysed(current(), deps, next) }
                .onFailure {
                    if (it is CancellationException) throw it
                    TrackLog.w("BitChord", "harmonic track $next failed: ${it.message}", it, null)
                }
            attempted += next
            if (myGeneration != generation) return
            val usable = deps.analyzer.analysisFor(next).isUsable
            TrackLog.d("BitChord", "harmonic measured $next usable=$usable", null)
            // Real-time: every landing re-sorts and re-applies immediately,
            // so the queue visibly jumps track by track while measuring.
            // Store-hit tracks land in milliseconds, so the first jumps
            // come almost at once.
            current()?.let { l ->
                runCatching { sortAndApply(l, deps, scopeIds) }
                    .onFailure { TrackLog.w("BitChord", "harmonic apply failed: ${it.message}", it, null) }
            }
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

    /** Folds the live 20-ahead window into the measured scope (never shrinks). */
    private fun extendScope(player: Player) {
        val window = upcomingIds(player).take(MAX_SORT_AHEAD)
        if (window.isEmpty()) return
        scopeIds = (scopeIds + window).distinct()
    }

    /** First window track with no usable analysis that this activation hasn't attempted. */
    private fun nextNeedingMeasure(player: Player, deps: Deps): String? =
        upcomingIds(player).take(MAX_SORT_AHEAD).firstOrNull {
            it !in attempted && !deps.analyzer.analysisFor(it).isUsable
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
        TrackLog.d("BitChord", "harmonic stand-down: restoring pre-sort order", null)
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
            .onFailure { TrackLog.w("BitChord", "harmonic resort failed: ${it.message}", it, null) }
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
        deps.cache.forceFullPull(listOf(id))
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
            TrackLog.w("BitChord", "harmonic sort gave up waiting on $id", null)
        }
    }

    /**
     * Rearranges the tracks still to come into the highest-scoring chain, in
     * one edit — see [QueueShuffle.applyOrder] for why one edit and why a
     * permutation.
     *
     * Only tracks from the measured scope move, and only among the slots they
     * already hold: everything beyond the scope, anything queued or dragged
     * since, and anything unmeasurable stays exactly where it is. The user and
     * AutoPlay sections sort separately so suggestions stay below the queue.
     */
    private fun sortAndApply(player: Player, deps: Deps, scopeIds: List<String>) {
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) return
        val upcoming = List(player.mediaItemCount - from) { player.getMediaItemAt(from + it) }
        val scope = scopeIds.toSet()
        val sortableSlots = upcoming.indices.filter { upcoming[it].mediaId in scope }
        if (sortableSlots.size <= 1) {
            TrackLog.d("BitChord", "harmonic apply: only ${sortableSlots.size} scope track(s) left, keeping order", null)
            return
        }
        val analyses = sortableSlots.associate { upcoming[it].mediaId to deps.analyzer.analysisFor(upcoming[it].mediaId) }
        val anchor = player.currentMediaItem?.let { analyses[it.mediaId] ?: deps.analyzer.analysisFor(it.mediaId) }
            ?.takeIf { it.isUsable }
        val vibe = AppSettings.harmonicVibe.value
        // Arc targets need comparable energies: min-max normalize the scope's
        // mean energies to 0..1. A flat scope (or none measured) reads 0.5
        // everywhere, which degrades exactly to the old pair-only greedy.
        val rawEnergies = analyses.mapValues { (_, analysis) -> meanEnergy(analysis) }
        val finite = rawEnergies.values.filterNotNull().filter { it.isFinite() }
        val eMin = finite.minOrNull() ?: 0.0
        val eMax = finite.maxOrNull() ?: 0.0
        val energies = rawEnergies.mapValues { (_, raw) ->
            if (raw == null || !raw.isFinite() || eMax <= eMin) 0.5
            else ((raw - eMin) / (eMax - eMin)).coerceIn(0.0, 1.0)
        }
        val (mixSlots, ownSlots) = sortableSlots.partition { upcoming[it].fromAutoplay }
        val sortedOwn = sortSection(ownSlots.map { upcoming[it] }, analyses, energies, anchor, vibe)
        val mixAnchor = sortedOwn.lastOrNull()?.let { analyses[it.mediaId] }?.takeIf { it.isUsable } ?: anchor
        val sorted = sortedOwn + sortSection(mixSlots.map { upcoming[it] }, analyses, energies, mixAnchor, vibe)
        // Back into slots: each sorted track takes the slot its predecessor in
        // the sorted order vacated, so non-scope tracks never shift.
        val positions = HashMap<String, ArrayDeque<Int>>(sortableSlots.size)
        sortableSlots.forEach { positions.getOrPut(upcoming[it].mediaId) { ArrayDeque() }.addLast(it) }
        val placed = sorted.map { positions[it.mediaId]?.removeFirstOrNull() ?: return }
        val order = upcoming.indices.map { slot ->
            val sortableAt = sortableSlots.indexOf(slot)
            if (sortableAt < 0) slot else placed[sortableAt]
        }
        TrackLog.d("BitChord", "harmonic sort placed ${sorted.size} of ${upcoming.size} upcoming", null)
        QueueShuffle.applyFromSession(player, from, order)
    }

    /**
     * The highest-scoring chain through [tracks], greedy forward from [anchor] —
     * but scored against the set arc, not just the last handover. At each step
     * the winner maximizes `pairScore − λ·|energy − target|`: a great handover
     * still beats the arc, but a pretty irrelevance no longer does. The slot
     * fraction runs 0..1 across this section, so the curve lands where the
     * listener is, not where measuring happened to finish. Unmeasurable tracks
     * keep their relative order at the end, as before.
     */
    private fun sortSection(
        tracks: List<MediaItem>,
        analyses: Map<String, TrackAnalysis>,
        energies: Map<String, Double>,
        anchor: TrackAnalysis?,
        vibe: Vibe,
    ): List<MediaItem> {
        val (usable, failed) = tracks.partition { analyses[it.mediaId]?.isUsable == true }
        val ordered = ArrayList<MediaItem>(usable.size)
        val remaining = usable.toMutableList()
        var cursor = anchor
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
                remaining.forEachIndexed { index, item ->
                    val candidate = analyses[item.mediaId] ?: return@forEachIndexed
                    val pair = findBestCandidate(current, candidate)?.candidateScore
                        ?: return@forEachIndexed
                    val arcPenalty = VIBE_LAMBDA * abs((energies[item.mediaId] ?: 0.5) - target)
                    val score = pair - arcPenalty
                    if (score > bestScore) {
                        bestScore = score
                        best = index
                    }
                }
                remaining.removeAt(best)
            }
            ordered += next
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
