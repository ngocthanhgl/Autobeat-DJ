package com.music.autobeat.playback

/**
 * Orchestrates all DJ-gated audio processors. Groups the per-deck
 * sends, EQ, loop vamp, splice guard, and loudness stage
 * behind a single handle so [CrossfadeController] receives
 * one composition rather than ten loose parameters.
 *
 * Each field exposes its interface directly (e.g. [EchoFilters],
 * [EqFilters]) so the controller's existing constructor wiring stays
 * intact. Role-swap at handoff is the same pattern as before.
 */
class DJEffects {

    val echoSendA = EchoSendProcessor()
    val echoSendB = EchoSendProcessor()
    var activeEcho: EchoSendProcessor = echoSendA
    var spareEcho: EchoSendProcessor = echoSendB

    val reverbSendA = ReverbProcessor()
    val reverbSendB = ReverbProcessor()
    var activeReverb: ReverbProcessor = reverbSendA
    var spareReverb: ReverbProcessor = reverbSendB

    val loopVampA = LoopVampProcessor()
    val loopVampB = LoopVampProcessor()
    var activeVamp: LoopVampProcessor = loopVampA
    var spareVamp: LoopVampProcessor = loopVampB

    val djEqA = DJBandEQ()
    val djEqB = DJBandEQ()
    var activeEq: DJBandEQ = djEqA
    var spareEq: DJBandEQ = djEqB

    val spliceGuardA = SpliceGuardProcessor()
    val spliceGuardB = SpliceGuardProcessor()

    val loudnessGainA = LoudnessGainProcessor()
    val loudnessGainB = LoudnessGainProcessor()
    var activeLoudness: LoudnessGainProcessor = loudnessGainA
    var spareLoudness: LoudnessGainProcessor = loudnessGainB

    /** Open all processors — tails ring out naturally. */
    fun open() {
        activeEcho.open()
        activeReverb.open()
        activeVamp.open()
        activeEq.open()
        activeLoudness.open()
    }

    // Full-audit F7: close/bail wipe tails immediately (they used to mirror
    // open(), ringing ~2.5 s on a skip-interrupt). Ring buffers are cleared
    // and gains parked.
    /** Close all processors — wipe tails immediately. */
    fun close() {
        activeEcho.clear()
        activeReverb.clear()
        activeVamp.clear()
        activeEq.open()
        activeLoudness.open()
    }

    /** Emergency bail — cut all tails immediately. */
    fun bail() {
        activeEcho.clear()
        activeReverb.clear()
        activeVamp.clear()
        activeEq.open()
        activeLoudness.open()
    }

    /** Reset all processors to idle state. */
    fun reset() {
        echoSendA.reset()
        echoSendB.reset()
        reverbSendA.reset()
        reverbSendB.reset()
        loopVampA.reset()
        loopVampB.reset()
        spliceGuardA.reset()
        spliceGuardB.reset()
        loudnessGainA.reset()
        loudnessGainB.reset()
    }

    /** Swap active/spare roles (handoff). */
    fun swapRoles() {
        val tmpEcho = activeEcho
        activeEcho = spareEcho
        spareEcho = tmpEcho

        val tmpReverb = activeReverb
        activeReverb = spareReverb
        spareReverb = tmpReverb

        val tmpVamp = activeVamp
        activeVamp = spareVamp
        spareVamp = tmpVamp

        val tmpEq = activeEq
        activeEq = spareEq
        spareEq = tmpEq

        val tmpLoudness = activeLoudness
        activeLoudness = spareLoudness
        spareLoudness = tmpLoudness
    }
}
