package com.music.autobeat.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * Shared motion tokens — the whole app's feel in one place.
 *
 * Restrained bounce: spring overshoot lives on press / drag-settle / sheet-settle /
 * artwork, while navigation and content crossfades keep Material [FastOutSlowInEasing].
 * New animation code must use these tokens instead of inventing one-off specs.
 */
object MotionTokens {
    // Typed as FiniteAnimationSpec so the tokens fit everywhere a tween or
    // spring fits: enter/exit fades, Crossfade, animate*AsState. A plain
    // AnimationSpec (infinite-repeatable capable) does not fit those sites.
    /** Press feedback (lyric rows, toggles, chips): quick with a hint of overshoot. */
    val PressSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 500f)

    /** Layout settle (sleeve, hero, splits, swipes): soft overshoot, GlassSpring feel. */
    val SettleSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 320f)

    /** IntSize variant of [SettleSpring] for animateContentSize. */
    val SettleSpringIntSize: FiniteAnimationSpec<IntSize> =
        spring(dampingRatio = 0.7f, stiffness = 320f)

    /** Dp variant of [SettleSpring] for animateDpAsState. */
    val SettleSpringDp: FiniteAnimationSpec<Dp> = spring(dampingRatio = 0.7f, stiffness = 320f)

    /** Fast fades (controls, glyphs, tooltips). */
    val FadeFast: FiniteAnimationSpec<Float> = tween(150, easing = FastOutSlowInEasing)

    /** Medium fades (panels, sheets content, bars). */
    val FadeMed: FiniteAnimationSpec<Float> = tween(220, easing = FastOutSlowInEasing)

    /** Color variant of [FadeMed] for animateColorAsState. */
    val FadeMedColor: AnimationSpec<Color> = tween(220, easing = FastOutSlowInEasing)

    /** Shared lyric ease so lyric rows move as one voice. */
    val LyricEase: Easing = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
}
