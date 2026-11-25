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
import androidx.compose.foundation.selection.toggleable
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
internal fun BubbleAppearanceSettings(prefs: SharedPreferences, grip: Boolean, key: (String) -> String = { it }, refresh: () -> Unit) {
    val hapticView = LocalView.current
    val sizeKey = key("float_size")
    var size by remember(sizeKey) { mutableIntStateOf(prefs.getInt(sizeKey, 2).takeIf { it in 0..4 } ?: 2) }
    // Stored choice survives Grab point; only the effective choice changes while it is on.
    val stored by rememberIdleBehavior(prefs, key)
    val extraSmall by rememberExtraSmall(prefs, key)
    val uniform by rememberUniformShape(prefs)
    val behavior = BubbleAppearance.effective(stored, grip, extraSmall)
    val idleDelay by rememberIdleDelay(prefs, key)
    var previewOpacity by remember { mutableIntStateOf(prefs.getInt("float_opacity", 65)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == "float_opacity") previewOpacity = changed.getInt(key, 65)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val searchKey = key("float_shrink_search")
    var search by remember(searchKey) { mutableStateOf(prefs.getBoolean(searchKey, false)) }
    Text(stringResource(R.string.bubble_size), style = MaterialTheme.typography.titleMedium)
    FloatingButtonPreview(BubbleAppearance.buttonSize(size, extraSmall), stored, grip, extraSmall, uniform, previewOpacity, idleDelay)
    val sizes = listOf(R.string.bubble_xs, R.string.bubble_s, R.string.bubble_m, R.string.bubble_l, R.string.bubble_xl)
    val sizeLabel = stringResource(R.string.bubble_size)
    val selectedSize = stringResource(sizes[size])
    Slider(value = size.toFloat(), valueRange = 0f..4f, steps = 3, enabled = !extraSmall,
        onValueChange = {
            val next = it.roundToInt().coerceIn(0, 4)
            if (next != size) {
                size = next
                ViewCompat.performHapticFeedback(hapticView, HapticFeedbackConstantsCompat.SEGMENT_TICK)
            }
        },
        onValueChangeFinished = { prefs.edit { putInt(sizeKey, size) }; refresh() },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = sizeLabel; stateDescription = selectedSize })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        sizes.forEachIndexed { index, label ->
            val color = if (index == size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Text(stringResource(label), color = color.copy(alpha = if (extraSmall) 0.38f else 1f),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    // Same slot either way so the layout does not jump when Extra small flips.
    StableText(listOf(stringResource(R.string.bubble_default_size), stringResource(R.string.bubble_extra_small_size_paused)),
        if (extraSmall) 1 else 0, style = MaterialTheme.typography.bodySmall)
    val extraSmallLabel = stringResource(R.string.bubble_extra_small)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).toggleable(extraSmall, role = Role.Switch, onValueChange = {
        ViewCompat.performHapticFeedback(hapticView, if (it) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
        // Size and Shrink smaller stay stored; they come back when Extra small turns off.
        prefs.edit { putBoolean(key("float_extra_small"), it) }
        refresh()
    }), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(extraSmallLabel, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.bubble_extra_small_sub), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = extraSmall, onCheckedChange = null)
    }
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
                    val enabled = BubbleAppearance.effective(value, grip, extraSmall) == value
                    val selected = behavior == value
                    Surface(Modifier.weight(1f).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = {
                        prefs.edit { putString(key("float_idle_style"), value.key); remove("float_shrink_always") }
                        refresh()
                    }), shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(12.dp).heightIn(min = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Extra small starts from the small circle, so the tiles show that.
                                if (value != BubbleAppearance.IdleBehavior.ALWAYS_DOT) AppearanceMark(if (extraSmall) 19 else 28)
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
    Reveal(grip) { Text(stringResource(R.string.bubble_dot_grip), style = MaterialTheme.typography.bodySmall) }
    Reveal(extraSmall) { Text(stringResource(R.string.bubble_small_extra_small), style = MaterialTheme.typography.bodySmall) }
    // Always shown so the layout never jumps; greyed out (value kept) when the idle choice has no delay.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bubble_search), Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (behavior.usesDelay) 1f else 0.38f))
        Switch(checked = search, enabled = behavior.usesDelay,
            onCheckedChange = { search = it; prefs.edit { putBoolean(searchKey, it) }; refresh() })
    }
    Reveal(behavior == BubbleAppearance.IdleBehavior.ALWAYS_DOT) {
        Text(stringResource(R.string.bubble_always_dot_help), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.bubble_dot_help), style = MaterialTheme.typography.bodySmall)
 }

@Composable
private fun rememberIdleBehavior(prefs: SharedPreferences, key: (String) -> String): State<BubbleAppearance.IdleBehavior> =
    rememberPref(prefs, key("float_idle_style")) { prefs, k -> BubbleAppearance.idleBehavior(prefs.getString(k, "full")) }

@Composable
private fun rememberExtraSmall(prefs: SharedPreferences, key: (String) -> String): State<Boolean> =
    rememberPref(prefs, key("float_extra_small")) { prefs, k -> prefs.getBoolean(k, false) }

@Composable
private fun rememberUniformShape(prefs: SharedPreferences): State<Boolean> =
    rememberPref(prefs, "float_shape") { prefs, k -> BubbleAppearance.uniformShape(prefs.getString(k, null)) }

/** Advanced: adaptive corners (fixed radius, rounder when smaller) or one uniform shape at every size. */
@Composable
internal fun ButtonShapeSetting(prefs: SharedPreferences, refresh: () -> Unit) {
    val uniform by rememberUniformShape(prefs)
    val adaptiveLabel = stringResource(R.string.bubble_shape_adaptive)
    val uniformLabel = stringResource(R.string.bubble_shape_uniform)
    ChoiceSetting(stringResource(R.string.bubble_shape), if (uniform) uniformLabel else adaptiveLabel, listOf(adaptiveLabel, uniformLabel)) { picked ->
        prefs.edit { putString("float_shape", if (picked == uniformLabel) "uniform" else "adaptive") }
        refresh()
    }
    StableText(listOf(stringResource(R.string.bubble_shape_adaptive_sub), stringResource(R.string.bubble_shape_uniform_sub)), if (uniform) 1 else 0,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun rememberIdleDelay(prefs: SharedPreferences, key: (String) -> String): State<Int> =
    rememberPref(prefs, key("float_idle_delay")) { prefs, k -> BubbleAppearance.idleDelayMs(prefs.getInt(k, BubbleAppearance.IDLE_DELAY_DEFAULT_MS)) }

@Composable
private fun <T> rememberPref(prefs: SharedPreferences, key: String, read: (SharedPreferences, String) -> T): State<T> {
    val state = remember(prefs, key) { mutableStateOf(read(prefs, key)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, changedKey -> if (changedKey == key) state.value = read(changed, key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Advanced: how long the button stays full size before shrinking. Greyed out when no idle choice uses it. */
@Composable
internal fun IdleDelaySetting(prefs: SharedPreferences, grip: Boolean, key: (String) -> String = { it }, refresh: () -> Unit) {
    val hapticView = LocalView.current
    val behavior = BubbleAppearance.effective(rememberIdleBehavior(prefs, key).value, grip, rememberExtraSmall(prefs, key).value)
    val stored by rememberIdleDelay(prefs, key)
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
        onValueChangeFinished = { prefs.edit { putInt(key("float_idle_delay"), delayMs) }; refresh() },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = value })
    StableText(listOf(stringResource(R.string.bubble_idle_delay_sub), stringResource(R.string.bubble_idle_delay_off)), if (enabled) 0 else 1,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AppearanceMark(size: Int) {
    Surface(Modifier.size(size.dp), shape = RoundedCornerShape(minOf(9, size / 2).dp), color = Color.White,
        border = BorderStroke(1.dp, Color.Black)) {
        if (size > 8) Icon(painterResource(R.drawable.ic_stat_sync), null, Modifier.padding((size * 0.2f).dp), tint = Color.Black)
    }
}

/** Foldables only (#113): opt-in separate settings per screen; turning it off confirms first. */
@Composable
internal fun SplitScreensSetting(split: Boolean, onChange: (Boolean) -> Unit) {
    var confirmOff by remember { mutableStateOf(false) }
    val label = stringResource(R.string.fold_split)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp).toggleable(split, role = Role.Switch, onValueChange = {
        if (it) onChange(true) else confirmOff = true
    }), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.fold_split_sub), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = split, onCheckedChange = null)
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text(stringResource(R.string.fold_split_off_title)) },
        text = { Text(stringResource(R.string.fold_split_off_body)) },
        confirmButton = { TextButton(onClick = { confirmOff = false; onChange(false) }) { Text(stringResource(R.string.fold_split_off_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** "Cover screen | Inner screen": which screen's settings the controls below edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenPicker(selected: FoldScreens.Screen, inUse: FoldScreens.Screen, onSelect: (FoldScreens.Screen) -> Unit) {
    val inUseLabel = stringResource(R.string.fold_in_use)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        FoldScreens.Screen.entries.forEachIndexed { index, screen ->
            val name = stringResource(if (screen == FoldScreens.Screen.COVER) R.string.fold_cover else R.string.fold_inner)
            SegmentedButton(
                selected = screen == selected,
                onClick = { onSelect(screen) },
                shape = SegmentedButtonDefaults.itemShape(index, FoldScreens.Screen.entries.size),
                modifier = Modifier.heightIn(min = 56.dp)
                    .semantics { contentDescription = if (screen == inUse) "$name, $inUseLabel" else name },
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name)
                        if (screen == inUse) Text(inUseLabel, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        }
    }
}

/** Per-screen position: placed by dragging on that screen; reset puts it back to the default spot. */
@Composable
internal fun PositionSetting(prefs: SharedPreferences, inUse: Boolean, key: (String) -> String, refresh: () -> Unit) {
    Text(stringResource(R.string.fold_position), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(if (inUse) R.string.fold_position_here else R.string.fold_position_elsewhere),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = {
        prefs.edit { remove(key("float_x")); remove(key("float_y")) }
        refresh()
    }) { Text(stringResource(R.string.fold_position_reset)) }
}
