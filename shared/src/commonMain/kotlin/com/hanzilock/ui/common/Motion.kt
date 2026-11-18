package com.hanzilock.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Material 3 motion, so every transition in the app moves the same way. On Android, the system's
// "Remove animations" setting scales all of these to nothing.

/** Arriving: fast, then settling gently into place. */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Leaving: picks up speed and goes. */
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** The old content is gone after [OUT] ms; the new one arrives over the next [IN]. */
private const val OUT = 90
private const val IN = 210

/** Material fade-through: for switching to unrelated content (another tab, loading → loaded). */
fun fadeThrough(): ContentTransform =
    (fadeIn(tween(IN, OUT, EmphasizedDecelerate)) + scaleIn(tween(IN, OUT, EmphasizedDecelerate), initialScale = 0.96f)) togetherWith
        fadeOut(tween(OUT, easing = EmphasizedAccelerate))

/** Material shared axis X: going one screen deeper ([forward]) or back. [distance] in pixels. */
fun sharedAxisX(forward: Boolean, distance: Int): ContentTransform {
    val d = if (forward) distance else -distance
    return (slideInHorizontally(tween(OUT + IN, easing = EmphasizedDecelerate)) { d } + fadeIn(tween(IN, OUT, EmphasizedDecelerate))) togetherWith
        (slideOutHorizontally(tween(OUT + IN, easing = EmphasizedDecelerate)) { -d } + fadeOut(tween(OUT, easing = EmphasizedAccelerate)))
}

/** Material shared axis Y: the next step of the same task. [distance] in pixels. */
fun sharedAxisY(distance: Int): ContentTransform =
    (slideInVertically(tween(OUT + IN, easing = EmphasizedDecelerate)) { distance } + fadeIn(tween(IN, OUT, EmphasizedDecelerate))) togetherWith
        (slideOutVertically(tween(OUT + IN, easing = EmphasizedDecelerate)) { -distance } + fadeOut(tween(OUT, easing = EmphasizedAccelerate)))

/** How far shared-axis content travels, in pixels. */
@Composable
fun axisDistance(): Int = with(LocalDensity.current) { 30.dp.roundToPx() }

/**
 * [AnimatedContent] whose outgoing content takes no taps. It stays on screen until the transition ends,
 * and a quick second tap on its buttons would act on what's showing now (score a word twice, open a screen twice).
 */
@Composable
fun <S> MotionContent(
    targetState: S,
    modifier: Modifier = Modifier,
    transitionSpec: AnimatedContentTransitionScope<S>.() -> ContentTransform = { fadeThrough() },
    label: String = "content",
    content: @Composable (S) -> Unit,
) {
    AnimatedContent(targetState, modifier, transitionSpec, label = label) { state ->
        val leaving = transition.targetState == EnterExitState.PostExit
        Box(if (leaving) Modifier.pointerInput(Unit) { swallowTouches() } else Modifier) { content(state) }
    }
}

private suspend fun PointerInputScope.swallowTouches() = awaitPointerEventScope {
    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
}
