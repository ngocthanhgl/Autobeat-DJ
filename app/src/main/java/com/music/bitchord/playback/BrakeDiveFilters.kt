package com.music.bitchord.playback

/**
 * The brake/dive effect riding the outgoing deck during transitions.
 * Applies a quadratic speed reduction to create a dramatic "swoop-down".
 */
interface BrakeDiveFilters {
    /** Aims the brake on the track fading out. [amount] 0..1. */
    fun outgoing(amount: Float)
    /** DJ-only backspin flag — when true the processor reads the ring backwards. */
    fun setBackspin(enabled: Boolean)

    /**
     * Advances the spinback sweep. [phase] 0..1 across the spin window, set
     * once per tick by the controller — the processor never steps it, so the
     * sweep cannot restart mid-window.
     */
    fun spinTo(phase: Float)

    /** Rides the brake back to zero so the track resumes normal speed. */
    fun ride()

    /** For callers with no audio sink — tests, and the default wiring. */
    object None : BrakeDiveFilters {
        override fun outgoing(amount: Float) = Unit
        override fun setBackspin(enabled: Boolean) = Unit
        override fun spinTo(phase: Float) = Unit
        override fun ride() = Unit
    }
}
