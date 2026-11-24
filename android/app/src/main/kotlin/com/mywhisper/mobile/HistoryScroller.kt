package com.mywhisper.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Row (0-based, within history) for a thumb at [fraction] of the track. Pure for unit tests. */
internal fun scrollerRowAt(fraction: Float, rows: Int): Int =
    if (rows <= 1) 0 else (fraction.coerceIn(0f, 1f) * (rows - 1)).let { kotlin.math.round(it).toInt() }

/** Thumb fraction for the first visible history [row]. Pure for unit tests. */
internal fun scrollerFractionOf(row: Int, rows: Int): Float =
    if (rows <= 1) 0f else (row.toFloat() / (rows - 1)).coerceIn(0f, 1f)

/**
 * Fast scroller for history: a thin track with day marks and a thumb on
 * the end edge. Grabbing the thumb (or the track) widens it, shows the
 * day under the finger in a bubble and ticks a haptic at each new day.
 *
 * [rowLabels] holds the day label of every history row in list order,
 * starting at list index [firstIndex]. It covers loaded pages only, so the
 * thumb shifts as more pages arrive.
 */
@Composable
internal fun HistoryFastScroller(
    state: LazyListState,
    firstIndex: Int,
    rowLabels: List<String>,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val rows = rowLabels.size
    if (rows < 2) return
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var trackPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastLabel by remember { mutableStateOf<String?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val scrolledFraction by remember(state, firstIndex, rows) {
        derivedStateOf { scrollerFractionOf(state.firstVisibleItemIndex - firstIndex, rows) }
    }
    val fraction = if (dragging) dragFraction else scrolledFraction
    val thumbWidth by animateDpAsState(if (dragging) 10.dp else 5.dp, label = "thumbWidth")
    val thumbHeight = 44.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val labels by rememberUpdatedState(rowLabels)

    fun moveTo(y: Float) {
        dragFraction = ((y - thumbHeightPx / 2) / (trackPx - thumbHeightPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val row = scrollerRowAt(dragFraction, labels.size)
        val label = labels.getOrNull(row)
        if (label != null && label != lastLabel) {
            if (lastLabel != null) ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK)
            lastLabel = label
        }
        scrollJob?.cancel()
        scrollJob = scope.launch { state.scrollToItem(firstIndex + row) }
    }

    AnimatedVisibility(visible || dragging, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxHeight().width(28.dp)
                .onSizeChanged { trackPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            lastLabel = null
                            ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.GESTURE_START)
                            moveTo(offset.y)
                        },
                        onDragEnd = { dragging = false; ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.GESTURE_END) },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        moveTo(change.position.y)
                    }
                },
        ) {
            val track = MaterialTheme.colorScheme.outlineVariant
            val mark = MaterialTheme.colorScheme.onSurfaceVariant
            val thumbY = ((trackPx - thumbHeightPx) * fraction).toInt()
            // One lane: the track line, day notches and thumb share its center,
            // so the thumb sits on the line and widens evenly to both sides.
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).width(12.dp).fillMaxHeight()) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width / 2
                    drawLine(track, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    // A notch where each day starts (skipped when too close to the last one).
                    val usable = size.height - thumbHeightPx
                    val half = 4.dp.toPx()
                    var lastY = -100f
                    for (i in 1 until rows) {
                        if (labels[i] == labels[i - 1]) continue
                        val y = thumbHeightPx / 2 + usable * scrollerFractionOf(i, rows)
                        if (y - lastY < 6.dp.toPx()) continue
                        drawLine(mark, Offset(x - half, y), Offset(x + half, y), strokeWidth = 1.5.dp.toPx())
                        lastY = y
                    }
                }
                Box(
                    Modifier.align(Alignment.TopCenter).offset { IntOffset(0, thumbY) }
                        .size(thumbWidth, thumbHeight).clip(RoundedCornerShape(50))
                        .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
    // Day bubble beside the thumb while dragging.
    if (dragging) {
        val thumbY = ((trackPx - thumbHeightPx) * fraction).toInt()
        Box(modifier.fillMaxHeight()) {
            Surface(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(density) { 40.dp.roundToPx() }, thumbY) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 3.dp,
            ) {
                Text(labels.getOrNull(scrollerRowAt(fraction, rows)).orEmpty(),
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
