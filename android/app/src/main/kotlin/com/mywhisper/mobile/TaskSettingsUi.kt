package com.mywhisper.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The compact, grouped pages used by the three task destinations. */
@Composable
internal fun TaskPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun DestinationRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** Keyboard setup and notification permission are intentionally optional. */
internal data class BubbleSetupState(
    val microphone: Boolean,
    val overlay: Boolean,
    val dictationService: Boolean,
    val speech: Boolean,
) {
    enum class Step { MICROPHONE, OVERLAY, DICTATION_SERVICE, SPEECH, COMPLETE }
    val next: Step get() = when {
        !microphone -> Step.MICROPHONE
        !overlay -> Step.OVERLAY
        !dictationService -> Step.DICTATION_SERVICE
        !speech -> Step.SPEECH
        else -> Step.COMPLETE
    }
    val completed: Int get() = listOf(microphone, overlay, dictationService, speech).count { it }
}

/** Default cloud endpoints require credentials; custom endpoints may be keyless. */
internal fun cloudCredentialsMissing(kind: String, url: String, apiKey: String): Boolean =
    kind == "Cloud" && url.isBlank() && apiKey.isBlank()

/** Use Android's semantic effects and compatibility fallback, respecting system settings. */
@Composable
internal fun FeedbackSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    Switch(
        checked = checked,
        modifier = modifier,
        enabled = enabled,
        onCheckedChange = onCheckedChange?.let { change ->
            { next ->
                if (next != checked) {
                    ViewCompat.performHapticFeedback(view, if (next) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
                    change(next)
                }
            }
        },
    )
}

/** Exclusive options use segmented buttons when they fit, radio choices otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceSetting(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    val view = LocalView.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelLarge
    var dialogOpen by remember { mutableStateOf(false) }
    fun select(option: String) {
        if (option != value) {
            ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.SEGMENT_TICK)
            onPick(option)
        }
        dialogOpen = false
    }
    if (options.size > 3) {
        DestinationRow(label, value) { dialogOpen = true }
        if (dialogOpen) {
            AlertDialog(
                onDismissRequest = { dialogOpen = false },
                title = { Text(label) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { RadioChoices(value, options, ::select) } },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val widest = options.maxOfOrNull { measurer.measure(AnnotatedString(it), style = labelStyle).size.width } ?: 0
                val fits = with(density) { (widest + 56.dp.toPx()) * options.size <= maxWidth.toPx() }
                if (fits) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = option == value,
                                onClick = { select(option) },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                label = { Text(option) },
                            )
                        }
                    }
                } else {
                    RadioChoices(value, options, ::select)
                }
            }
        }
    }
}

@Composable
private fun RadioChoices(value: String, options: List<String>, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = value == option, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == option, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A switch setting is a single accessible, tappable row. */
@Composable
internal fun ToggleSetting(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { next ->
            if (next != checked) {
                ViewCompat.performHapticFeedback(view, if (next) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
                onChange(next)
            }
        }),
    )
}

internal object NotificationPermissionPolicy {
    fun shouldOffer(sdk: Int, allowed: Boolean, requested: Boolean, offered: Boolean): Boolean =
        sdk >= 33 && !allowed && !requested && !offered
}
