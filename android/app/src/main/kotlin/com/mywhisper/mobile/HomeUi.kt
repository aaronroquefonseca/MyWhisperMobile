package com.mywhisper.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Last dictation problem, shown on Activity until the next clean dictation. */
internal object DictationIssue {
    const val PREF_KIND = "dictation_issue_kind"
    const val PREF_DETAIL = "dictation_issue_detail"
    const val SPEECH = "speech"
    const val CLEANUP = "cleanup"
    /** Cleanup is on but no cleanup provider is enabled. */
    const val CLEANUP_SETUP = "cleanup_setup"

    /** How text cleanup went for one dictation. */
    enum class Cleanup { OK, FAILED, UNCONFIGURED }

    /** Cleanup outcome from the core's per-entry notes. Pure for unit tests. */
    @JvmStatic
    fun cleanupOutcome(notes: List<String>): Cleanup = when {
        "no enabled cleanup entries" in notes -> Cleanup.UNCONFIGURED
        cleanupAllFailed(notes) -> Cleanup.FAILED
        else -> Cleanup.OK
    }

    /**
     * Short bubble copy for a failed transcription: "set one up first" only
     * when no speech provider exists at all, otherwise "all failed".
     * Pure for unit tests.
     */
    @JvmStatic
    fun needsProviderSetup(msg: String, anyProviderEnabled: Boolean): Boolean =
        causeOf(msg) == Cause.NO_PROVIDER && !anyProviderEnabled

    /** Plain-language category for a speech failure. Pure for unit tests. */
    enum class Cause { NO_PROVIDER, SIGN_IN, UNREACHABLE, MISSING_ADDRESS, MICROPHONE, OTHER }

    @JvmStatic
    fun causeOf(msg: String): Cause {
        val m = msg.lowercase()
        return when {
            "no server stt configured" in m || "no server is configured" in m -> Cause.NO_PROVIDER
            "has no url" in m -> Cause.MISSING_ADDRESS
            "401" in m || "403" in m || "unauthorized" in m || "api key" in m || "forbidden" in m -> Cause.SIGN_IN
            "connect" in m || "timed out" in m || "timeout" in m || "dns" in m || "unreachable" in m ||
                "request:" in m -> Cause.UNREACHABLE
            "microphone" in m || "record_audio" in m || ("audio" in m && "permission" in m) -> Cause.MICROPHONE
            else -> Cause.OTHER
        }
    }

    /**
     * True when cleanup was attempted and every provider failed. Notes
     * come from the core (`"<name>: ok"` / `"<name>: <error>"`); the
     * disabled / empty-chain / snippet notes are not attempts.
     * Pure for unit tests.
     */
    @JvmStatic
    fun cleanupAllFailed(notes: List<String>): Boolean {
        val attempts = notes.filterNot {
            it == "cleanup disabled" || it == "no enabled cleanup entries" || it.startsWith("snippets:")
        }
        return attempts.isNotEmpty() && attempts.none { it.endsWith(": ok") }
    }
}

/** Day header for a history entry: Today, Yesterday, or a short date. Pure for unit tests. */
internal fun historyDayLabel(
    recordedAtSecs: Long,
    today: LocalDate,
    zone: ZoneId,
    todayWord: String,
    yesterdayWord: String,
): String {
    val day = Instant.ofEpochSecond(recordedAtSecs).atZone(zone).toLocalDate()
    return when (day) {
        today -> todayWord
        today.minusDays(1) -> yesterdayWord
        else -> day.format(
            if (day.year == today.year) DateTimeFormatter.ofPattern("EEE d MMM")
            else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
        )
    }
}

internal fun historyTimeLabel(recordedAtSecs: Long, zone: ZoneId): String =
    Instant.ofEpochSecond(recordedAtSecs).atZone(zone).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/**
 * Split control: the switch toggles the button, the rest of the card opens its
 * settings (the Android "primary switch" pattern, marked by a divider and chevron).
 */
@Composable
internal fun FloatingButtonCard(
    enabled: Boolean,
    busy: Boolean,
    status: String,
    action: String?,
    onAction: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val title = stringResource(R.string.setup_float)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_float_open), onClick = onOpen)
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(Modifier.size(40.dp), shape = CircleShape,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(painterResource(R.drawable.ic_nav_dictation), null, Modifier.padding(9.dp),
                            tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                VerticalDivider(Modifier.height(32.dp), color = MaterialTheme.colorScheme.outlineVariant)
                FeedbackSwitch(
                    checked = enabled, enabled = !busy, onCheckedChange = onToggle,
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = title },
                )
            }
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(start = 62.dp, bottom = 6.dp)) { Text(action) }
            }
        }
    }
}

/** Problem report for the last dictation, with a way to fix it. */
@Composable
internal fun DictationIssueCard(title: String, body: String, detail: String?, fixLabel: String, onFix: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text(body, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (!detail.isNullOrBlank()) {
                Text(detail, Modifier.padding(top = 4.dp, end = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onFix) { Text(fixLabel) }
        }
    }
}

/**
 * Header row for every expandable section: the whole row is the touch
 * target, so the ripple fills the card width instead of hugging the text.
 */
@Composable
internal fun ExpandHeader(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "expandChevron")
    val state = stringResource(if (expanded) R.string.bubble_advanced_open else R.string.bubble_advanced_closed)
    Row(
        modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.padding(start = 8.dp).graphicsLayer { rotationZ = chevron },
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Collapsed-by-default card; [content] reveals under an [ExpandHeader]. */
@Composable
internal fun CollapsibleCard(title: String, summary: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.animateContentSize()) {
            ExpandHeader(title, summary, open, onToggle)
            if (open) Column(Modifier.padding(bottom = 12.dp), content = content)
        }
    }
}

/** Lets a child of a padded card reach the card edges (full-width ripple). */
internal fun Modifier.bleedHorizontal(amount: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2))
    layout(p.width - extra * 2, p.height) { p.place(-extra, 0) }
}

@Composable
internal fun HistoryDayHeader(label: String) {
    Text(label, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/**
 * One transcription: tap copies it, long-press expands to show the
 * original (pre-cleanup) transcription plus per-entry actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryEntryCard(
    text: String,
    original: String,
    meta: String,
    expanded: Boolean,
    busy: Boolean,
    onCopy: () -> Unit,
    onToggleExpanded: () -> Unit,
    onCopyOriginal: () -> Unit,
    onDelete: () -> Unit,
) {
    val view = LocalView.current
    val cleaned = original.isNotBlank() && original.trim() != text.trim()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .combinedClickable(
                    onClickLabel = stringResource(R.string.home_copy),
                    onLongClickLabel = stringResource(if (expanded) R.string.home_collapse else R.string.home_show_original),
                    onClick = onCopy,
                    onLongClick = {
                        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.LONG_PRESS)
                        onToggleExpanded()
                    },
                )
                .animateContentSize()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        ) {
            val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "historyChevron")
            Row {
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Same as a long-press: reveal the original and the actions.
                IconButton(onClick = onToggleExpanded, modifier = Modifier.offset(x = 4.dp, y = (-2).dp).size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown,
                        stringResource(if (expanded) R.string.home_collapse else R.string.home_show_original),
                        Modifier.graphicsLayer { rotationZ = chevron }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.home_original), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (cleaned) original else stringResource(R.string.home_original_same),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (cleaned) TextButton(onClick = onCopyOriginal, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.home_copy_original))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            }
        }
    }
}
