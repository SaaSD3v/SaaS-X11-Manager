package com.saas.x11manager.util

import androidx.compose.animation.core.*

object AnimationUtils {
    const val DURATION_FAST = 150
    const val DURATION_MEDIUM = 200
    const val DURATION_SLOW = 300
    const val DURATION_SCREEN_TRANSITION = 400
    const val DURATION_CARD_FADE = 180

    val STANDARD_EASING = FastOutSlowInEasing

    fun <T> fastSpec(): TweenSpec<T> = tween(durationMillis = DURATION_FAST, easing = STANDARD_EASING)
    fun <T> mediumSpec(): TweenSpec<T> = tween(durationMillis = DURATION_MEDIUM, easing = STANDARD_EASING)
    fun <T> slowSpec(): TweenSpec<T> = tween(durationMillis = DURATION_SLOW, easing = STANDARD_EASING)
    fun <T> screenTransitionSpec(): TweenSpec<T> = tween(durationMillis = DURATION_SCREEN_TRANSITION, easing = STANDARD_EASING)
    fun <T> cardFadeSpec(): TweenSpec<T> = tween(durationMillis = DURATION_CARD_FADE, easing = LinearOutSlowInEasing)
    fun <T> fadeInSpec(): TweenSpec<T> = tween(durationMillis = DURATION_SCREEN_TRANSITION, easing = STANDARD_EASING)
    fun <T> fadeOutSpec(): TweenSpec<T> = tween(durationMillis = DURATION_FAST, easing = FastOutLinearInEasing)
}
