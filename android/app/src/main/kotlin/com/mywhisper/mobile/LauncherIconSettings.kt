package com.mywhisper.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LauncherIconSettings() {
    val context = LocalContext.current
    val manager = remember(context) { LauncherIconManager(context.applicationContext) }
    var selected by remember { mutableStateOf(manager.selected()) }
    var applying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LauncherIconPicker(
        selected = selected,
        nightly = context.resources.getBoolean(R.bool.launcher_default_cobalt),
        enabled = !applying,
        onSelect = { style ->
            if (style != selected) {
                applying = true
                message = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { manager.select(style) } }
                    selected = manager.selected()
                    applying = false
                    message = if (result.isSuccess) R.string.icon_style_applied else R.string.icon_style_error
                }
            }
        },
    )
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = if (it == R.string.icon_style_error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
internal fun LauncherIconPicker(
    selected: LauncherIconStyle,
    nightly: Boolean,
    enabled: Boolean = true,
    onSelect: (LauncherIconStyle) -> Unit,
) {
    val default = LauncherIconStyle.defaultPalette(nightly)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.icon_style_help), style = MaterialTheme.typography.bodyMedium)
        IconChoiceSurface(selected == LauncherIconStyle.DEFAULT, enabled,
            { onSelect(LauncherIconStyle.DEFAULT) }, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LauncherIconPreview(default)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.icon_style_default), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            if (nightly) R.string.icon_style_nightly_default else R.string.icon_style_stable_default,
                            stringResource(default.label),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RadioButton(selected == LauncherIconStyle.DEFAULT, onClick = null, enabled = enabled)
            }
        }
        LauncherIconStyle.entries.filter { it != LauncherIconStyle.DEFAULT }.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { style ->
                    IconChoiceSurface(selected == style, enabled, { onSelect(style) },
                        Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                LauncherIconPreview(style)
                                RadioButton(selected == style, onClick = null, enabled = enabled)
                            }
                            Text(stringResource(style.label), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconChoiceSurface(selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick,
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        content = content,
    )
}

@Composable
private fun LauncherIconPreview(style: LauncherIconStyle) {
    // Same 108dp artwork cropped to Android's 72dp viewport; circle is an example mask.
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(style.background)),
        contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null,
            colorFilter = ColorFilter.tint(Color(style.foreground)), modifier = Modifier.requiredSize(72.dp),
        )
    }
}
