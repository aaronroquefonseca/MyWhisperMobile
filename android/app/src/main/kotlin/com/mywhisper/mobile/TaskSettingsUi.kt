package com.mywhisper.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun DestinationRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Text("›", modifier = Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
