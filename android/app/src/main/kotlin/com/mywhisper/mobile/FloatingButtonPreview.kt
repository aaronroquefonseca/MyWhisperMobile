package com.mywhisper.mobile

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val HOLD_MS = 1500L
private const val MORPH_MS = 300
private const val FADE_MS = 400

/**
 * Appearance preview only. Recording gestures belong to the real floating button.
 * Delayed shrinking loops forward (full, morph, hold, cross-fade back to full) and is sped up,
 * with a dashed outline of the resting shape so the change reads at a glance.
 */
@Composable
internal fun FloatingButtonPreview(size: Int, behavior: BubbleAppearance.IdleBehavior, grip: Boolean, extraSmall: Boolean,
                                   uniform: Boolean, opacity: Int, idleDelayMs: Int) {
    val context = LocalContext.current
    // System "Remove animations": show before and after side by side instead of a loop.
    val reduceMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val active = BubbleAppearance.effective(behavior, grip, extraSmall)
    val loops = active.usesDelay && !reduceMotion
    val rest = if (extraSmall) BubbleAppearance.Shape.SMALL else BubbleAppearance.Shape.FULL
    val idleShape = BubbleAppearance.shape(behavior, elapsed = true, search = false, shrinkSearch = false, grip, extraSmall, busy = false)
    var elapsed by remember { mutableStateOf(false) }
    // Each loop is a new layer; the old one cross-fades out still shrunk, so the preview is never empty.
    var cycle by remember { mutableIntStateOf(0) }
    var previousSize by remember { mutableIntStateOf(size) }
    var showSelectedSize by remember { mutableStateOf(false) }
    LaunchedEffect(size) {
        // Choices without a loop (Start as dot) briefly show the resting size so the slider still has feedback.
        if (size != previousSize) {
            previousSize = size
            showSelectedSize = true
            delay(1000)
            showSelectedSize = false
        }
    }
    LaunchedEffect(size, active, extraSmall, loops) {
        elapsed = false
        if (!loops) return@LaunchedEffect
        while (true) {
            delay(HOLD_MS)
            elapsed = true
            delay(MORPH_MS + HOLD_MS)
            // Restart with a cross-fade, never a reverse morph: growing back would read as "grows when idle".
            elapsed = false
            cycle++
            delay(FADE_MS.toLong())
        }
    }
    val shape = when {
        showSelectedSize -> rest
        loops -> if (elapsed) idleShape else rest
        else -> BubbleAppearance.shape(behavior, elapsed = false, search = false, shrinkSearch = false, grip, extraSmall, busy = false)
    }
    val delayText = BubbleAppearance.seconds(idleDelayMs)
    val caption = when {
        active.usesDelay && (elapsed || reduceMotion) -> stringResource(R.string.bubble_preview_after, delayText)
        active.usesDelay -> stringResource(R.string.bubble_preview_start)
        active == BubbleAppearance.IdleBehavior.ALWAYS_DOT -> stringResource(R.string.bubble_preview_dot)
        else -> stringResource(R.string.bubble_tile_no_shrink)
    }
    // One stable description: TalkBack should not announce every loop step.
    val description = stringResource(R.string.bubble_preview) + ". " + when {
        active.usesDelay -> stringResource(if (idleShape == BubbleAppearance.Shape.DOT) R.string.bubble_tile_dot else R.string.bubble_tile_small) +
            ", " + stringResource(R.string.bubble_tile_after, delayText)
        active == BubbleAppearance.IdleBehavior.ALWAYS_DOT -> stringResource(R.string.bubble_preview_dot)
        else -> stringResource(R.string.bubble_tile_no_shrink)
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.clearAndSetSemantics { contentDescription = description }) {
            if (loops) Text(stringResource(R.string.bubble_preview_sped_up), Modifier.align(Alignment.TopEnd).padding(12.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    if (reduceMotion && active.usesDelay) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PreviewButton(size, rest, rest, grip, uniform, opacity, ghost = false)
                            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PreviewButton(size, idleShape, rest, grip, uniform, opacity, ghost = true)
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Crossfade(cycle, animationSpec = tween(FADE_MS), label = "previewLoop") { layer ->
                                PreviewButton(size, if (layer == cycle) shape else idleShape, rest, grip = false, uniform, opacity, ghost = loops)
                            }
                            // Outside the cross-fade so the handle never dims while layers swap.
                            GripMark(BubbleAppearance.diameter(size, rest).dp, grip, opacity)
                        }
                    }
                }
                // Full width + centered text: both captions share one centre while they cross-fade.
                Crossfade(caption, Modifier.fillMaxWidth(), label = "previewCaption") {
                    Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The button, plus (when [ghost]) a dashed outline of the resting shape that fades in once it shrinks. */
@Composable
private fun PreviewButton(size: Int, shape: BubbleAppearance.Shape, rest: BubbleAppearance.Shape, grip: Boolean,
                          uniform: Boolean, opacity: Int, ghost: Boolean) {
    val diameter by animateDpAsState(BubbleAppearance.diameter(size, shape).dp, tween(MORPH_MS), label = "previewSize")
    val roundness by animateFloatAsState(BubbleAppearance.roundness(shape), tween(MORPH_MS), label = "previewRoundness")
    val ghostAlpha by animateFloatAsState(if (ghost && shape != rest) 1f else 0f, tween(MORPH_MS), label = "previewGhost")
    val restDiameter = BubbleAppearance.diameter(size, rest).dp
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    // Sized to the resting shape so the grip and caption never move while the button shrinks.
    Box(Modifier.size(restDiameter), contentAlignment = Alignment.Center) {
        GripMark(restDiameter, grip, opacity)
        Box(Modifier.size(restDiameter).graphicsLayer { alpha = ghostAlpha }.drawBehind {
            val stroke = 1.dp.toPx()
            val radius = corner(restDiameter, BubbleAppearance.roundness(rest), uniform).toPx() - stroke / 2
            drawRoundRect(outline, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                cornerRadius = CornerRadius(radius), style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        })
        Surface(Modifier.size(diameter).graphicsLayer { alpha = FloatState.overlayWindowOpacity(opacity) },
            shape = RoundedCornerShape(corner(diameter, roundness, uniform)), color = Color.White,
            border = BorderStroke(1.dp, Color.Black)) {
            if (shape != BubbleAppearance.Shape.DOT) {
                Icon(painterResource(R.drawable.ic_stat_sync), null, Modifier.padding(diameter * 0.2f), tint = Color.Black)
            }
        }
    }
}

/** Drawn above a [restDiameter] box, outside layout: toggling Grab point fades it in without moving the button. */
@Composable
private fun GripMark(restDiameter: Dp, grip: Boolean, opacity: Int) {
    val gripAlpha by animateFloatAsState(if (grip) 1f else 0f, tween(MORPH_MS), label = "previewGrip")
    Box(Modifier.size(restDiameter)) {
        Surface(Modifier.align(Alignment.TopCenter).offset(y = -12.dp).width(28.dp).height(4.dp)
            .graphicsLayer { alpha = gripAlpha * FloatState.overlayWindowOpacity(opacity) },
            shape = RoundedCornerShape(2.dp), color = Color(BubbleAppearance.gripColor)) {}
    }
}

// Same corner rule as BubbleSurface so the preview matches the real button.
private fun corner(diameter: Dp, roundness: Float, uniform: Boolean): Dp {
    val squircle = BubbleAppearance.cornerRadius(diameter.value, 1f, uniform).dp
    return squircle + (diameter / 2 - squircle) * roundness
}
