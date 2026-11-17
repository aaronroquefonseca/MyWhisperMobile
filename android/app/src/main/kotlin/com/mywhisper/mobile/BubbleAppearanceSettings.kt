package com.mywhisper.mobile

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

@Composable
internal fun BubbleAppearanceSettings(prefs: SharedPreferences, grip: Boolean, refresh: () -> Unit) {
    val hapticView = LocalView.current
    var size by remember { mutableIntStateOf(prefs.getInt("float_size", 2).takeIf { it in 0..4 } ?: 2) }
    // Stored choice survives Grab point; only the effective choice changes while it is on.
    val stored by rememberIdleBehavior(prefs)
    val behavior = BubbleAppearance.withGrip(stored, grip)
    val idleDelay by rememberIdleDelay(prefs)
    var previewOpacity by remember { mutableIntStateOf(prefs.getInt("float_opacity", 65)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == "float_opacity") previewOpacity = changed.getInt(key, 65)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var search by remember { mutableStateOf(prefs.getBoolean("float_shrink_search", false)) }
    Text(stringResource(R.string.bubble_size), style = MaterialTheme.typography.titleMedium)
    FloatingButtonPreview(BubbleAppearance.size(size), behavior, grip, previewOpacity, idleDelay)
    val sizes = listOf(R.string.bubble_xs, R.string.bubble_s, R.string.bubble_m, R.string.bubble_l, R.string.bubble_xl)
    val sizeLabel = stringResource(R.string.bubble_size)
    val selectedSize = stringResource(sizes[size])
    Slider(value = size.toFloat(), valueRange = 0f..4f, steps = 3,
        onValueChange = {
            val next = it.roundToInt().coerceIn(0, 4)
            if (next != size) {
                size = next
                ViewCompat.performHapticFeedback(hapticView, HapticFeedbackConstantsCompat.SEGMENT_TICK)
            }
        },
        onValueChangeFinished = { prefs.edit { putInt("float_size", size) }; refresh() },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = sizeLabel; stateDescription = selectedSize })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        sizes.forEachIndexed { index, label ->
            Text(stringResource(label), color = if (index == size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(stringResource(R.string.bubble_default_size), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.bubble_idle), style = MaterialTheme.typography.titleMedium)
    val choices = listOf(
        Triple(BubbleAppearance.IdleBehavior.FULL, R.string.bubble_tile_keep, R.string.bubble_tile_no_shrink),
        Triple(BubbleAppearance.IdleBehavior.SMALL_AFTER_DELAY, R.string.bubble_tile_small, R.string.bubble_tile_after),
        Triple(BubbleAppearance.IdleBehavior.DOT_AFTER_DELAY, R.string.bubble_tile_dot, R.string.bubble_tile_after),
        Triple(BubbleAppearance.IdleBehavior.ALWAYS_DOT, R.string.bubble_tile_start_dot, R.string.bubble_tile_immediate),
    )
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (value, title, timing) ->
                    val enabled = !grip || !value.usesDot
                    val selected = behavior == value
                    Surface(Modifier.weight(1f).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = {
                        prefs.edit { putString("float_idle_style", value.key); remove("float_shrink_always") }
                        refresh()
                    }), shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(12.dp).heightIn(min = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (value != BubbleAppearance.IdleBehavior.ALWAYS_DOT) AppearanceMark(28)
                                if (value.usesDelay) Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (value != BubbleAppearance.IdleBehavior.FULL) AppearanceMark(if (value.usesDot) 8 else 19)
                            }
                            val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
                            Text(stringResource(title), color = textColor, style = MaterialTheme.typography.bodyMedium)
                            Text(if (value.usesDelay) stringResource(timing, BubbleAppearance.seconds(idleDelay)) else stringResource(timing), color = textColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (grip) Text(stringResource(R.string.bubble_dot_grip), style = MaterialTheme.typography.bodySmall)
    // Always shown so the layout never jumps; greyed out (value kept) when the idle choice has no delay.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bubble_search), Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (behavior.usesDelay) 1f else 0.38f))
        Switch(checked = search, enabled = behavior.usesDelay,
            onCheckedChange = { search = it; prefs.edit { putBoolean("float_shrink_search", it) }; refresh() })
    }
    if (behavior == BubbleAppearance.IdleBehavior.ALWAYS_DOT) {
        Text(stringResource(R.string.bubble_always_dot_help), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.bubble_dot_help), style = MaterialTheme.typography.bodySmall)
 }

@Composable
private fun rememberIdleBehavior(prefs: SharedPreferences): State<BubbleAppearance.IdleBehavior> =
    rememberPref(prefs, "float_idle_style") { BubbleAppearance.idleBehavior(it.getString("float_idle_style", "full")) }

@Composable
private fun rememberIdleDelay(prefs: SharedPreferences): State<Int> =
    rememberPref(prefs, "float_idle_delay") { BubbleAppearance.idleDelayMs(it.getInt("float_idle_delay", BubbleAppearance.IDLE_DELAY_DEFAULT_MS)) }

@Composable
private fun <T> rememberPref(prefs: SharedPreferences, key: String, read: (SharedPreferences) -> T): State<T> {
    val state = remember(prefs) { mutableStateOf(read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, changedKey -> if (changedKey == key) state.value = read(changed) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Advanced: how long the button stays full size before shrinking. Greyed out when no idle choice uses it. */
@Composable
internal fun IdleDelaySetting(prefs: SharedPreferences, grip: Boolean, refresh: () -> Unit) {
    val hapticView = LocalView.current
    val behavior = BubbleAppearance.withGrip(rememberIdleBehavior(prefs).value, grip)
    val stored by rememberIdleDelay(prefs)
    var delayMs by remember(stored) { mutableIntStateOf(stored) }
    val options = BubbleAppearance.idleDelayOptionsMs
    val enabled = behavior.usesDelay
    val label = stringResource(R.string.bubble_idle_delay)
    val value = stringResource(R.string.bubble_idle_delay_value, BubbleAppearance.seconds(delayMs))
    val alpha = if (enabled) 1f else 0.38f
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    Slider(value = options.indexOf(delayMs).coerceAtLeast(0).toFloat(), valueRange = 0f..options.lastIndex.toFloat(),
        steps = options.size - 2, enabled = enabled,
        onValueChange = {
            val next = options[it.roundToInt().coerceIn(options.indices)]
            if (next != delayMs) {
                delayMs = next
                ViewCompat.performHapticFeedback(hapticView, HapticFeedbackConstantsCompat.SEGMENT_TICK)
            }
        },
        onValueChangeFinished = { prefs.edit { putInt("float_idle_delay", delayMs) }; refresh() },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = value })
    Text(stringResource(if (enabled) R.string.bubble_idle_delay_sub else R.string.bubble_idle_delay_off),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AppearanceMark(size: Int) {
    Surface(Modifier.size(size.dp), shape = RoundedCornerShape(minOf(9, size / 2).dp), color = Color.White,
        border = BorderStroke(1.dp, Color.Black)) {
        if (size > 8) Icon(painterResource(R.drawable.ic_stat_sync), null, Modifier.padding((size * 0.2f).dp), tint = Color.Black)
    }
}
