package com.chatscroll.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal object ReaderMotion {
    const val Feedback = 160
    const val Reorder = 220
    const val Page = 240
    const val Overlay = 200
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** A native button keeps its ripple and touch target; only its painted layer responds to pressure. */
@Composable
internal fun ReaderMotionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1f,
        tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing), label = "buttonPress")
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = modifier) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { content() }
    }
}

/** Keeps a fullscreen window alive through its short exit, then releases it without timed delays. */
@Composable
internal fun ReaderMotionDialog(
    onDismiss: () -> Unit,
    properties: DialogProperties,
    content: @Composable BoxScope.(dismiss: () -> Unit) -> Unit
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible.isIdle, visible.currentState, visible.targetState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) currentDismiss()
    }
    val dismiss = { visible.targetState = false }
    Dialog(onDismissRequest = dismiss, properties = properties) {
        AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(ReaderMotion.Overlay, easing = ReaderMotion.Easing)) +
                scaleIn(tween(ReaderMotion.Overlay, easing = ReaderMotion.Easing), initialScale = 0.97f),
            exit = fadeOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)) +
                scaleOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing), targetScale = 0.98f)) {
            Box(Modifier.fillMaxSize()) { content(dismiss) }
        }
    }
}

/** Only the arriving screen is composed: departing readers save their position once. */
@Composable
internal fun ReaderPage(pageKey: String, returning: Boolean, content: @Composable BoxScope.() -> Unit) {
    key(pageKey) {
        var arrived by remember { mutableStateOf(false) }
        val progress by animateFloatAsState(if (arrived) 1f else 0f,
            animationSpec = tween(ReaderMotion.Page, easing = ReaderMotion.Easing), label = "pageArrival")
        val direction = remember { if (returning) -1f else 1f }
        val distance = with(LocalDensity.current) { 12.dp.toPx() } * direction
        LaunchedEffect(Unit) { arrived = true }
        // Compose's frame clock applies the system animator duration scale, including zero.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = progress
            translationX = distance * (1f - progress)
        }, content = content)
    }
}
