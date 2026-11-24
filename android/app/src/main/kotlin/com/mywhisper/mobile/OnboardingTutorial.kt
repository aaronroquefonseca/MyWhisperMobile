package com.mywhisper.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.delay

/** Fake "working" time before the example text lands: long enough to read as real. */
internal const val TUTORIAL_WORKING_MS = 300L

/** Release before this toggles (tap to start, tap to stop), like the real Smart mode. */
private const val TUTORIAL_TAP_MS = 300L

private enum class MockState { IDLE, RECORDING, WORKING }

/**
 * Practice canvas for onboarding: a fake text field with a fake floating
 * button. Nothing is recorded; finishing always "types" the next example
 * sentence after [TUTORIAL_WORKING_MS].
 */
@Composable
internal fun DictationTutorial() {
    val samples = stringArrayResource(R.array.tutorial_samples)
    val view = LocalView.current
    var state by remember { mutableStateOf(MockState.IDLE) }
    var toggled by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (state == MockState.WORKING) {
            delay(TUTORIAL_WORKING_MS)
            typed = listOf(typed, samples[next % samples.size]).filter { it.isNotEmpty() }.joinToString(" ")
            next++
            state = MockState.IDLE
        }
    }
    fun start() {
        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.GESTURE_START)
        state = MockState.RECORDING
    }
    fun finish() {
        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.GESTURE_END)
        toggled = false
        state = MockState.WORKING
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.tutorial_app_name), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            // Fake text field: the place the dictation lands in another app.
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 140.dp).animateContentSize(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (state == MockState.IDLE) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary),
            ) {
                Box(Modifier.padding(14.dp)) {
                    if (typed.isEmpty()) {
                        Text(stringResource(R.string.tutorial_placeholder), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    } else {
                        Text(typed, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(state, Modifier.weight(1f), label = "tutorialStatus",
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }) { s ->
                    when (s) {
                        MockState.RECORDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            MockBars()
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(if (toggled) R.string.tutorial_listening_tap else R.string.tutorial_listening_hold),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        MockState.WORKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.tutorial_working), style = MaterialTheme.typography.bodyMedium)
                        }
                        MockState.IDLE -> Text(stringResource(R.string.tutorial_hint), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(12.dp))
                MockFloatingButton(
                    state = state,
                    onPress = {
                        when {
                            state == MockState.WORKING -> false
                            state == MockState.RECORDING && toggled -> { finish(); false }
                            else -> { start(); true }
                        }
                    },
                    onRelease = { heldMs ->
                        if (state != MockState.RECORDING) return@MockFloatingButton
                        if (heldMs < TUTORIAL_TAP_MS) toggled = true else finish()
                    },
                    onAccessibilityTap = {
                        if (state == MockState.RECORDING) finish() else if (state == MockState.IDLE) { start(); toggled = true }
                    },
                )
            }
            if (typed.isNotEmpty()) {
                TextButton(onClick = { typed = "" }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.tutorial_clear))
                }
            }
        }
    }
}

/** Same look as the real button (white, black outline, MyWhisper mark). */
@Composable
private fun MockFloatingButton(
    state: MockState,
    onPress: () -> Boolean,
    onRelease: (heldMs: Long) -> Unit,
    onAccessibilityTap: () -> Unit,
) {
    val ring by animateColorAsState(
        if (state == MockState.RECORDING) MaterialTheme.colorScheme.primary else Color.Black, label = "mockRing",
    )
    val label = stringResource(R.string.tutorial_button)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Color.White)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { onAccessibilityTap(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val started = System.currentTimeMillis()
                    val tracking = press()
                    val released = tryAwaitRelease()
                    // A cancelled gesture (finger slid off) ends like a long hold.
                    if (tracking) release(if (released) System.currentTimeMillis() - started else Long.MAX_VALUE)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = Color.Transparent, border = BorderStroke(if (state == MockState.RECORDING) 3.dp else 1.dp, ring)) {}
        if (state == MockState.WORKING) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_stat_sync), null, Modifier.size(30.dp), tint = Color.Black)
        }
    }
}

@Composable
private fun MockBars() {
    val transition = rememberInfiniteTransition(label = "mockBars")
    val color = MaterialTheme.colorScheme.primary
    Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0, 140, 280, 70, 210).forEach { offset ->
            val h by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420, delayMillis = offset), RepeatMode.Reverse), label = "bar")
            Box(Modifier.width(4.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** Step dots for paged flows. */
@Composable
internal fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by androidx.compose.animation.core.animateDpAsState(if (i == current) 20.dp else 8.dp, label = "dot")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor",
            )
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}
