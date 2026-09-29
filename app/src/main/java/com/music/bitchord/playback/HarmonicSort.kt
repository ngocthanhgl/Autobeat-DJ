package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackAnalyzer
import com.music.bitchord.playback.smart.findBestCandidate
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

/**
 * Harmonic Sort as an edit to the queue, not a playback mode — the same shape
 * as [QueueShuffle], but the order is earned by measurement rather than drawn
 * by chance.
 *
 * Turning it on snapshots the queue, measures the key and tempo of the tracks
 * still to come (at most [MAX_SORT_AHEAD] of them), and rearranges those into
 * the chain where each handover scores highest — the same pair scorer the DJ
 * planner trusts ([findBestCandidate]), run greedily forward from the track
 * that is playing. Turning it off puts the queue back the way it was found.
 *
 * The measuring is the hard part, and it is deliberately sequential. The
 * analyzer drains through one or two single-threaded FIFO lanes, each decode
 * costs seconds and tens of megabytes, and the disk cache holds about thirty
 * worst-case tracks — firing the whole scope at once would queue minutes of
 * work, pin the CPU, and thrash the cache into evicting the very bytes being
 * measured. So one track at a time: pull its bytes, ask for its analysis,
 * wait for the answer (or the timeout), and move on. Tracks already measured —
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

    private val _active = MutableStateFlow(false)

    /** Whether the queue is currently held in harmonic order. */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Media ids in their pre-sort order. Empty while the sort is off. */
    private var original: List<String> = emptyList()
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
                .onFailure { TrackLog.w("BitChord", "harmonic shuffle stand-down failed: ${it.message}", it) }
        }
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) {
            TrackLog.w(
                "BitChord",
                "harmonic toggle: nothing ahead (from=$from count=${player.mediaItemCount})",
            )
            return
        }
        original = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        val scopeIds = original.drop(from).take(MAX_SORT_AHEAD)
        if (scopeIds.isEmpty()) {
            original = emptyList()
            return
        }
        _active.value = true
        _progress.value = Progress(0, scopeIds.size)
        val myGeneration = ++generation
        worker = deps.scope.launch {
            TrackLog.d("BitChord", "harmonic worker started scope=${scopeIds.size}")
            for ((index, id) in scopeIds.withIndex()) {
                if (myGeneration != generation) return@launch
                // Guarded per track: one unmeasurable id must not take the
                // other nineteen down with it, silently or otherwise.
                runCatching { ensureAnalysed(current(), deps, id) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        TrackLog.w("BitChord", "harmonic track $id failed: ${it.message}", it)
                    }
                if (myGeneration != generation) return@launch
                TrackLog.d(
                    "BitChord",
                    "harmonic measured $id usable=${deps.analyzer.analysisFor(id).isUsable}",
                    about = id,
                )
                _progress.value = Progress(index + 1, scopeIds.size)
            }
            val live = current() ?: return@launch
            if (myGeneration != generation) return@launch
            runCatching { applyHarmonicOrder(live, deps, scopeIds) }
                .onFailure { TrackLog.w("BitChord", "harmonic apply failed: ${it.message}", it) }
            _progress.value = null
        }
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
        if (_active.value) restore(player)
        _active.value = false
        _progress.value = null
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
            TrackLog.w("BitChord", "harmonic sort gave up waiting on $id", about = id)
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
    private fun applyHarmonicOrder(player: Player, deps: Deps, scopeIds: List<String>) {
        val from = player.currentMediaItemIndex + 1
        if (from >= player.mediaItemCount) return
        val upcoming = List(player.mediaItemCount - from) { player.getMediaItemAt(from + it) }
        val scope = scopeIds.toSet()
        val sortableSlots = upcoming.indices.filter { upcoming[it].mediaId in scope }
        if (sortableSlots.size <= 1) {
            TrackLog.d("BitChord", "harmonic apply: only ${sortableSlots.size} scope track(s) left, keeping order")
            return
        }
        val analyses = sortableSlots.associate { upcoming[it].mediaId to deps.analyzer.analysisFor(upcoming[it].mediaId) }
        val anchor = player.currentMediaItem?.let { analyses[it.mediaId] ?: deps.analyzer.analysisFor(it.mediaId) }
            ?.takeIf { it.isUsable }
        val (mixSlots, ownSlots) = sortableSlots.partition { upcoming[it].fromAutoplay }
        val sortedOwn = sortSection(ownSlots.map { upcoming[it] }, analyses, anchor)
        val mixAnchor = sortedOwn.lastOrNull()?.let { analyses[it.mediaId] }?.takeIf { it.isUsable } ?: anchor
        val sorted = sortedOwn + sortSection(mixSlots.map { upcoming[it] }, analyses, mixAnchor)
        // Back into slots: each sorted track takes the slot its predecessor in
        // the sorted order vacated, so non-scope tracks never shift.
        val positions = HashMap<String, ArrayDeque<Int>>(sortableSlots.size)
        sortableSlots.forEach { positions.getOrPut(upcoming[it].mediaId) { ArrayDeque() }.addLast(it) }
        val placed = sorted.map { positions[it.mediaId]?.removeFirstOrNull() ?: return }
        val order = upcoming.indices.map { slot ->
            val sortableAt = sortableSlots.indexOf(slot)
            if (sortableAt < 0) slot else placed[sortableAt]
        }
        TrackLog.d("BitChord", "harmonic sort placed ${sorted.size} of ${upcoming.size} upcoming")
        QueueShuffle.applyFromSession(player, from, order)
    }

    /**
     * The highest-scoring chain through [tracks], greedy forward from [anchor]:
     * at each step the remaining track pairing best with the last placed one
     * joins it. Unmeasurable tracks keep their relative order at the end —
     * pushing them there rather than leaving them interleaved, so the chain
     * that plays is unbroken.
     */
    private fun sortSection(
        tracks: List<MediaItem>,
        analyses: Map<String, TrackAnalysis>,
        anchor: TrackAnalysis?,
    ): List<MediaItem> {
        val (usable, failed) = tracks.partition { analyses[it.mediaId]?.isUsable == true }
        val ordered = ArrayList<MediaItem>(usable.size)
        val remaining = usable.toMutableList()
        var cursor = anchor
        while (remaining.isNotEmpty()) {
            val current = cursor
            val next = if (current == null) {
                remaining.removeAt(0)
            } else {
                var best = 0
                var bestScore = Double.NEGATIVE_INFINITY
                remaining.forEachIndexed { index, item ->
                    val candidate = analyses[item.mediaId] ?: return@forEachIndexed
                    val score = findBestCandidate(current, candidate)?.candidateScore
                        ?: return@forEachIndexed
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
