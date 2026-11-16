package com.mywhisper.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun DestinationRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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

internal fun providerKindAvailable(kind: String, localSupported: Boolean): Boolean = kind != "Local" || localSupported

internal fun availableProviderKinds(kinds: List<String>, localSupported: Boolean): List<String> =
    kinds.filter { providerKindAvailable(it, localSupported) }

@Composable
internal fun LocalUnavailableNote() {
    ListItem(
        headlineContent = { Text(stringResource(R.string.stt_models_title)) },
        supportingContent = { Text(stringResource(R.string.local_need_full)) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Long lists stay inside a bounded, searchable dialog, clear of system bars. */
@Composable
internal fun SearchableChoiceSetting(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    DestinationRow(label, value) { query = ""; open = true }
    if (open) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.40f).dp.coerceAtMost(320.dp)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (options.size > 8) {
                        OutlinedTextField(query, { query = it }, singleLine = true,
                            label = { Text(stringResource(R.string.ux_search_options)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    val filtered = options.filter { it.contains(query.trim(), ignoreCase = true) }
                    val listState = rememberLazyListState()
                    LaunchedEffect(query) {
                        listState.scrollToItem(if (query.isBlank()) options.indexOf(value).coerceAtLeast(0) else 0)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight).selectableGroup(), state = listState) {
                        if (filtered.isEmpty()) item { Text(stringResource(R.string.ux_no_matches), Modifier.padding(vertical = 16.dp)) }
                        items(filtered) { option ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(value == option, role = Role.RadioButton, onClick = { open = false; onPick(option) })
                                .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(selected = value == option, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(option, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

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
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                    activeBorderColor = MaterialTheme.colorScheme.primary,
                                ),
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
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { next ->
            if (next != checked) {
                ViewCompat.performHapticFeedback(view, if (next) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
                onChange(next)
            }
        }),
    )
}

/** Selected filters use the same system-primary highlight as switches and sliders. */
@Composable
internal fun primaryFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/** API key / bearer token input, masked by default with an eye toggle so the value can be checked or copied. */
@Composable
internal fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    errorText: String? = null,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.stt_apikey)) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError && errorText != null) ({ Text(errorText) }) else null,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(if (shown) R.string.stt_apikey_hide else R.string.stt_apikey_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
