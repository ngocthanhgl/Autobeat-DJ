package com.music.autobeat.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/**
 * DJ-only brake/dive effect. Two voicings:
 *
 * Plain brake: a direct gain duck driven by the aimed amount (the controller
 * ramps it ~30x/s, which is the smoothing — no per-buffer glide needed).
 *
 * Backspin: a vinyl spinback. The controller sets the spin phase once per
 * tick across the energy-scaled window (1.5..2.5 s, never re-armed
 * mid-window), and this processor reads its tap ring backwards
 * continuously — reverse speed 0.5x → 4x with linear interpolation, level
 * held through the first half then diving. That is the hand dragging the
 * record back, not a power-off.
 *
 * The tap ring keeps the last 6 s as float mono-mixed per channel, so both
 * PCM-16 and FLOAT_32 chains spin (the old Short ring bowed out on float,
 * which is what most modern outputs negotiate). 6 s because the mean
 * reverse rate (~2.25x) over a 2.5 s window consumes ~5.6 s of source —
 * anything shorter wraps into stale audio at the tail, which is exactly
 * where the whip must stay razor.
 *
 * Processes buffers in place, exactly like [EchoSendProcessor].
 */
@UnstableApi
class BrakeDiveProcessor : BaseAudioProcessor() {

    companion object {
        const val MAX_BRAKE_AMOUNT = 1.0f
        const val BRAKE_DIVE_FACTOR = 0.97f
        // Reverse sweep bounds across the spin window, in x playback rate.
        const val SPIN_START_SPEED = 0.5f
        const val SPIN_END_SPEED = 4.0f
    }

    @Volatile
    private var targetBrakeAmount: Float = 0f
    @Volatile
    private var isBackspin = false
    // Spin phase 0..1 across the window, set by the controller once per tick.
    // Negative = not spinning. Never stepped here: the controller owns the
    // clock, so a tick cannot restart the sweep mid-window (that re-arm is
    // what used to chop the reverse into sub-millisecond blips).
    @Volatile
    private var spinPhase: Float = -1f
    // Hand on the record: 1 = one decisive pull, 2-3 = re-grabbed mid-spin.
    // Latched at spin start (never mid-window — a changing grab count would
    // jump the sweep phase).
    @Volatile
    private var spinGrabs: Int = 1

    private var encoding = C.ENCODING_INVALID
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var sampleRate = 48000
    // Tap ring, float interleaved, newest at ringPos. 6 s covers the longest
    // spin window (2.5 s at ~2.25x mean reverse ≈ 5.6 s of source) with
    // run-up behind the hand hitting the record.
    private var ring = FloatArray(0)
    private var ringFrames = 0
    private var ringPos = 0
    private var ringFilled = 0
    // Reverse cursor in frames (fractional — interpolated below). Re-seated
    // to the newest tap whenever a spin starts, then walks backwards for the
    // whole window without ever jumping.
    private var reversePos = 0f
    // Last phase actually rendered (audio-rate stepping below walks from
    // here to spinPhase inside one buffer, so the sweep never stair-steps
    // at the controller's ~30 Hz tick).
    private var lastPhase = -1f
    // Wow/flutter oscillator phase + one-pole lowpass state per channel
    // (for the scrub-brightness lift).
    private var flutterPhase = 0f
    private var lpState = FloatArray(0)

    /** Aims the brake. [amount] 0..1 where 1 = full stop. */
    fun setBrake(amount: Float) {
        targetBrakeAmount = amount.coerceIn(0f, MAX_BRAKE_AMOUNT)
    }

    fun setBackspin(enabled: Boolean) {
        if (enabled == isBackspin) return
        isBackspin = enabled
        if (enabled) {
            spinPhase = -1f
            reversePos = -1f
        } else {
            spinPhase = -1f
        }
    }

    /** Advances the spinback sweep. [phase] 0..1 across the spin window. */
    fun spinTo(phase: Float, grabs: Int = 1) {
        val p = phase.coerceIn(0f, 1f)
        if (spinPhase < 0f) {
            // Spin start: seat the cursor on the newest tapped frame so the
            // rewind begins exactly where the forward play was, and latch
            // the hand for the whole sweep.
            reversePos = ringPos.toFloat()
            spinGrabs = grabs.coerceIn(1, 4)
            lastPhase = -1f
            flutterPhase = 0f
        }
        spinPhase = p
    }

    /** Rides the brake back to zero so the track resumes normal speed. */
    fun ride() {
        setBrake(0f)
        isBackspin = false
        spinPhase = -1f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if ((inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.channelCount < 1
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        bytesPerFrame = (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2) * channelCount
        ringFrames = (sampleRate * 6.0).toInt().coerceAtLeast(48000)
        ring = FloatArray(ringFrames * channelCount)
        ringPos = 0
        ringFilled = 0
        spinPhase = -1f
        return inputAudioFormat
    }

    override fun onFlush() {
        ringPos = 0
        ringFilled = 0
        spinPhase = -1f
        lastPhase = -1f
    }

    override fun onReset() {
        targetBrakeAmount = 0f
        isBackspin = false
        spinPhase = -1f
        lastPhase = -1f
        ringPos = 0
        ringFilled = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        tapRing(inputBuffer, frameCount)

        val spinning = isBackspin && spinPhase >= 0f && ringFilled > channelCount * 256
        if (spinning) {
            renderSpin(outputBuffer, frameCount)
            // Still consume the input forward to keep the pipeline clock —
            // the deck keeps playing under the hand; only what is heard runs
            // backwards.
            inputBuffer.position(inputBuffer.position() + frameCount * bytesPerFrame)
            outputBuffer.flip()
            return
        }

        val amount = targetBrakeAmount
        if (amount <= 0f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        // Plain brake: one direct duck. The controller ramps [amount] every
        // tick, so the curve across the window comes from the caller.
        val brakeFactor = 1f - amount * BRAKE_DIVE_FACTOR
        if (encoding == C.ENCODING_PCM_FLOAT) {
            repeat(frameCount) {
                repeat(channelCount) {
                    outputBuffer.putFloat((inputBuffer.float * brakeFactor).coerceIn(-1f, 1f))
                }
            }
        } else {
            repeat(frameCount) {
                repeat(channelCount) {
                    outputBuffer.putShort(clampToShort(inputBuffer.short.toFloat() * brakeFactor))
                }
            }
        }
        outputBuffer.flip()
    }

    /** Copies the incoming frames into the tap ring as float. Cheap, always on. */
    private fun tapRing(inputBuffer: ByteBuffer, frameCount: Int) {
        if (ring.isEmpty()) return
        val pos = inputBuffer.position()
        val order = inputBuffer.order()
        inputBuffer.order(ByteOrder.nativeOrder())
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until frameCount * channelCount) {
                ring[ringPos] = inputBuffer.getFloat(pos + i * 4).coerceIn(-1f, 1f)
                ringPos = (ringPos + 1) % ring.size
            }
        } else {
            for (i in 0 until frameCount * channelCount) {
                ring[ringPos] = (inputBuffer.getShort(pos + i * 2).toFloat() / 32768f).coerceIn(-1f, 1f)
                ringPos = (ringPos + 1) % ring.size
            }
        }
        inputBuffer.order(order)
        ringFilled = min(ringFilled + frameCount * channelCount, ring.size)
    }

    /**
     * The spinback: walk the ring backwards at an exponential reverse rate
     * with linear interpolation, stepped per audio frame from the last
     * rendered phase to the controller's current one. Exponential (not
     * linear) because a hand yank starts under the music and whips past it
     * — slow drag, violent end. Grab wobble rides on speed (the hand
     * re-catching the record), a ~5.5 Hz flutter keeps the read head alive,
     * and scrub brightness lifts with reverse speed. Level holds ~80%
     * through the first 40% then dives to true zero at the cut, so the
     * exit is a landing, not a mute.
     */
    private fun renderSpin(outputBuffer: ByteBuffer, frameCount: Int) {
        val grabs = spinGrabs.coerceIn(1, 4)
        val target = spinPhase.coerceIn(0f, 1f)
        if (lastPhase < 0f) lastPhase = target
        val start = lastPhase.coerceIn(0f, 1f)
        lastPhase = target
        if (lpState.size != channelCount) lpState = FloatArray(channelCount)
        val twoPi = (2f * kotlin.math.PI).toFloat()
        val flutterStep = twoPi * 5.5f / sampleRate.coerceAtLeast(8000)
        outputBuffer.order(ByteOrder.nativeOrder())
        repeat(frameCount) { i ->
            val raw = if (frameCount > 1) start + (target - start) * (i.toFloat() / (frameCount - 1)) else target
            // Grab stutter on phase (re-catch hesitation, smoothstepped).
            val f = (raw * grabs).coerceIn(0f, grabs.toFloat())
            val seg = floor(f).toInt().coerceAtMost(grabs - 1)
            var fr = (f - seg).coerceIn(0f, 1f)
            fr = fr * fr * (3f - 2f * fr)
            val phase = ((seg + fr) / grabs).coerceIn(0f, 1f)
            // Exponential yank: 0.5x under the music, 4x whip at the cut.
            var rev = SPIN_START_SPEED * Math.pow((SPIN_END_SPEED / SPIN_START_SPEED).toDouble(), phase.toDouble()).toFloat()
            // Hand wobble on speed: ±18% at 2 cycles per grab.
            rev *= 1f + 0.18f * kotlin.math.sin(twoPi * grabs * 2f * phase)
            // Wow/flutter on the read head: ±2% at ~5.5 Hz.
            flutterPhase += flutterStep
            if (flutterPhase > twoPi) flutterPhase -= twoPi
            reversePos -= rev * (1f + 0.02f * kotlin.math.sin(flutterPhase))
            // Envelope: hold, then smooth dive to true zero.
            val env = ((phase - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val gain = 0.8f * (1f - env * env * (3f - 2f * env))
            // Scrub brightness: HF lift follows reverse speed.
            val lift = 0.6f * ((rev - SPIN_START_SPEED) / (SPIN_END_SPEED - SPIN_START_SPEED)).coerceIn(0f, 1f)
            for (ch in 0 until channelCount) {
                val x = ringAt(reversePos, ch)
                val lp = lpState[ch] + 0.1f * (x - lpState[ch])
                lpState[ch] = lp
                val s = (x + (x - lp) * lift) * gain
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    outputBuffer.putFloat(s.coerceIn(-1f, 1f))
                } else {
                    outputBuffer.putShort(clampToShort(s * 32767f))
                }
            }
        }
    }

    /** Interpolated tap-ring read at a fractional frame position, channel [ch]. */
    private fun ringAt(framePos: Float, ch: Int): Float {
        if (ring.isEmpty()) return 0f
        val size = ring.size
        fun at(frame: Int): Float {
            val f = ((frame % ringFrames) + ringFrames) % ringFrames
            return ring[f * channelCount + ch.coerceIn(0, channelCount - 1)]
        }
        val f0 = kotlin.math.floor(framePos).toInt()
        val frac = (framePos - f0).coerceIn(0f, 1f)
        return at(f0) + (at(f0 + 1) - at(f0)) * frac
    }

    private fun clampToShort(value: Float): Short =
        value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    // Full-audit F6: active purely on aimed target or an armed spin. The old
    // glide clause kept a fresh/seeked processor in the chain running a
    // pointless x1.0 copy on every PCM-16 playback (stock included).
    override fun isActive(): Boolean = targetBrakeAmount > 0f || (isBackspin && spinPhase >= 0f)
}
