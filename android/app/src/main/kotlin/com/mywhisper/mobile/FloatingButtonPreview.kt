package com.mywhisper.mobile

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Appearance preview only. Recording gestures belong to the real floating button. */
@Composable
internal fun FloatingButtonPreview(size: Int, behavior: BubbleAppearance.IdleBehavior, grip: Boolean, opacity: Int, idleDelayMs: Int) {
    var elapsed by remember { mutableStateOf(false) }
    var previousSize by remember { mutableIntStateOf(size) }
    var showSelectedSize by remember { mutableStateOf(false) }
    LaunchedEffect(size) {
        if (size != previousSize) {
            previousSize = size
            showSelectedSize = true
            delay(1000)
            showSelectedSize = false
        }
    }
    LaunchedEffect(size, behavior, grip, idleDelayMs) {
        elapsed = false
        if (behavior.usesDelay) {
            delay(idleDelayMs.toLong())
            elapsed = true
        }
    }
    val shape = if (showSelectedSize) BubbleAppearance.Shape.FULL
        else BubbleAppearance.shape(behavior, elapsed, false, false, grip, busy = false)
    val diameter by animateDpAsState(BubbleAppearance.diameter(size, shape).dp, label = "previewSize")
    val description = stringResource(R.string.bubble_preview)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                Column(Modifier.graphicsLayer { alpha = FloatState.overlayWindowOpacity(opacity) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (grip) Surface(Modifier.width(28.dp).height(4.dp), shape = RoundedCornerShape(2.dp), color = Color(BubbleAppearance.gripColor)) {}
                    Surface(Modifier.size(diameter),
                        shape = RoundedCornerShape(minOf(16.dp, diameter / 2)), color = Color.White,
                        border = BorderStroke(1.dp, Color.Black)) {
                        if (shape != BubbleAppearance.Shape.DOT) {
                            Icon(painterResource(R.drawable.ic_stat_sync), null, Modifier.padding(diameter * 0.2f), tint = Color.Black)
                        }
                    }
                }
            }
            Text(stringResource(R.string.bubble_preview_ready), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
