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
    var behavior by remember { mutableStateOf(BubbleAppearance.idleBehavior(prefs.getString("float_idle_style", "full"))) }
    var previewOpacity by remember { mutableIntStateOf(prefs.getInt("float_opacity", 65)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == "float_opacity") previewOpacity = changed.getInt(key, 65)
            if (key == "float_idle_style") behavior = BubbleAppearance.idleBehavior(changed.getString(key, "full"))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var search by remember { mutableStateOf(prefs.getBoolean("float_shrink_search", false)) }
    Text(stringResource(R.string.bubble_size), style = MaterialTheme.typography.titleMedium)
    FloatingButtonPreview(BubbleAppearance.size(size), behavior, grip, previewOpacity)
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
                        behavior = value
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
                            Text(stringResource(timing), color = textColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (grip) Text(stringResource(R.string.bubble_dot_grip), style = MaterialTheme.typography.bodySmall)
    if (behavior.usesDelay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bubble_search), Modifier.weight(1f))
            Switch(checked = search, enabled = !grip || !behavior.usesDot,
                onCheckedChange = { search = it; prefs.edit { putBoolean("float_shrink_search", it) }; refresh() })
        }
    }
    if (behavior == BubbleAppearance.IdleBehavior.ALWAYS_DOT) {
        Text(stringResource(R.string.bubble_always_dot_help), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.bubble_dot_help), style = MaterialTheme.typography.bodySmall)
 }

@Composable
private fun AppearanceMark(size: Int) {
    Surface(Modifier.size(size.dp), shape = RoundedCornerShape(minOf(9, size / 2).dp), color = Color.White,
        border = BorderStroke(1.dp, Color.Black)) {
        if (size > 8) Icon(painterResource(R.drawable.ic_stat_sync), null, Modifier.padding((size * 0.2f).dp), tint = Color.Black)
    }
}
