package com.mywhisper.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Config app mirroring the desktop Settings screens: Setup (IME +
 * mic behavior), Speech to Text (server/cloud chain), Text Cleanup
 * (LLM chain), Dictionary, Activity (usage), History (+ privacy),
 * Sync (desktop-compatible device sync). All edits round-trip through
 * the core's settings JSON, so the schema stays identical to desktop.
 */
class MainActivity : ComponentActivity() {

    private val settingsState: MutableState<JSONObject?> = mutableStateOf(null)
    private val statusState = mutableStateOf("")
    private val micGranted = mutableStateOf(false)
    private val workers = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Speech language codes (stored code, shown label). */
    private val langs = listOf(
        "auto" to "Auto-detect", "en" to "English", "es" to "Spanish",
        "de" to "German", "fr" to "French", "it" to "Italian",
        "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
        "ru" to "Russian", "uk" to "Ukrainian", "tr" to "Turkish",
        "ar" to "Arabic", "hi" to "Hindi", "zh" to "Chinese",
        "ja" to "Japanese", "ko" to "Korean", "ca" to "Catalan",
        "gl" to "Galician", "eu" to "Basque", "sv" to "Swedish",
        "da" to "Danish", "fi" to "Finnish", "no" to "Norwegian",
        "cs" to "Czech", "ro" to "Romanian", "hu" to "Hungarian",
        "el" to "Greek",
    )

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted.value = granted
        if (!granted) toast("Mic permission is required for dictation")
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) toast("Without notifications the sync status icon stays hidden")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        reload()
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        try {
            settingsState.value = CoreBridge.getSettings().getOrThrow()
            val app = application as? MyWhisperApp
            statusState.value = app?.coreError ?: "core ready"
        } catch (e: Exception) {
            statusState.value = "core error: ${e.message}"
        }
    }

    private fun save() {
        val s = settingsState.value ?: return
        try {
            CoreBridge.putSettings(s).getOrThrow()
        } catch (e: Exception) {
            toast("save failed: ${e.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun bgToast(msg: String) = runOnUiThread { toast(msg) }

    private fun imeEnabled(): Boolean {
        // NOTE: Settings.Secure ENABLED_INPUT_METHODS is unreadable for
        // targetSdk 34+. InputMethodManager is the allowed path.
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    private fun imeSeenActive(): String {
        val ts = getSharedPreferences("mw_state", MODE_PRIVATE)
            .getLong("ime_seen_ts", 0L)
        if (ts <= 0L) return ""
        val ago = (System.currentTimeMillis() - ts) / 1000
        return when {
            ago < 60 -> "seen active just now"
            ago < 3600 -> "seen active ${ago / 60}m ago"
            else -> "seen active ${ago / 3600}h ago"
        }
    }

    @Composable
    private fun Root() {
        val s = settingsState.value
        val theme = s?.optJSONObject("preferences")?.optString("theme", "System") ?: "System"
        MaterialTheme(
            colorScheme = when (theme) {
                "Light" -> lightColorScheme()
                "Dark" -> darkColorScheme()
                else -> if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            },
        ) {
            var tab by remember { mutableIntStateOf(0) }
            val tabs = listOf("Setup", "Speech", "Cleanup", "Words", "Activity", "History", "Sync")
            Scaffold { pad ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad),
                ) {
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        tabs.forEachIndexed { i, t ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i },
                                text = { Text(t) },
                            )
                        }
                    }
                    if (s == null) {
                        Text(
                            statusState.value,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        when (tab) {
                            0 -> SetupScreen(s)
                            1 -> SttScreen(s)
                            2 -> CleanupScreen(s)
                            3 -> DictionaryScreen(s)
                            4 -> ActivityScreen()
                            5 -> HistoryScreen(s)
                            6 -> SyncScreen(s)
                        }
                    }
                }
            }
        }
    }

    // -- shared widgets -------------------------------------------------

    @Composable
    private fun Section(title: String, body: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                body()
            }
        }
    }

    @Composable
    private fun DropDown(
        label: String,
        value: String,
        options: List<String>,
        onPick: (String) -> Unit,
    ) {
        var open by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
            Row {
                OutlinedButton(onClick = { open = true }) { Text("Change") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o) },
                            onClick = { open = false; onPick(o) },
                        )
                    }
                }
            }
        }
    }

    /**
     * Chain list with a left grab handle: press and drag immediately
     * to reorder (no long-press needed — the handle is dedicated).
     * Calls [onMove] with the JSON array indices; the caller persists.
     *
     * Targeting reads the LazyColumn's live layout info every event
     * (never a cached map), the drag offset resets on each swap, and
     * only one drag runs at a time — no runaway multi-row dragging.
     * The dragged row lifts with a soft tonal wash + shadow instead
     * of a hard full-bleed highlight.
     */
    @Composable
    private fun ReorderableEntries(
        items: List<JSONObject>,
        idOf: (JSONObject) -> String,
        onMove: (from: Int, to: Int) -> Unit,
        row: @Composable (entry: JSONObject, handle: @Composable () -> Unit) -> Unit,
    ) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragIdx by remember { mutableIntStateOf(-1) }
        var accumulated by remember { mutableFloatStateOf(0f) }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            items(items, key = { idOf(it) }) { item ->
                val id = idOf(item)
                val isDrag = dragging == id
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .graphicsLayer {
                            shadowElevation = if (isDrag) 14f else 0f
                            scaleX = if (isDrag) 1.015f else 1f
                            scaleY = if (isDrag) 1.015f else 1f
                        }
                        .zIndex(if (isDrag) 1f else 0f)
                        .background(
                            if (isDrag) MaterialTheme.colorScheme.surfaceContainerHighest
                            else Color.Transparent,
                            androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        ),
                ) {
                    row(item) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.pointerInput(id) {
                                detectDragGestures(
                                    onDragStart = {
                                        if (dragging != null) return@detectDragGestures
                                        dragging = id
                                        dragIdx = items.indexOfFirst { idOf(it) == id }
                                        accumulated = 0f
                                    },
                                    onDragEnd = { dragging = null; dragIdx = -1 },
                                    onDragCancel = { dragging = null; dragIdx = -1 },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val active = dragging
                                        if (active == null || active != id || dragIdx < 0) {
                                            return@detectDragGestures
                                        }
                                        accumulated += dragAmount.y
                                        val visible = listState.layoutInfo.visibleItemsInfo
                                        val info = visible.firstOrNull { it.key == active }
                                            ?: return@detectDragGestures
                                        val center = info.offset + info.size / 2f + accumulated
                                        val target = visible.firstOrNull { v ->
                                            center >= v.offset && center < v.offset + v.size
                                        }?.index ?: dragIdx
                                        if (target in items.indices && target != dragIdx) {
                                            onMove(dragIdx, target)
                                            dragIdx = target
                                            accumulated = 0f
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    // -- Setup ----------------------------------------------------------

    @Composable
    private fun SetupScreen(s: JSONObject) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Status") {
                Text(statusState.value, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Server-only build: configure a self-hosted or cloud speech service under Speech. On-device models come later.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Section("1 · Enable the input method") {
                Text(
                    if (imeEnabled()) "MyWhisper is enabled as an input method."
                    else "MyWhisper is not enabled yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }) { Text("Open input-method settings") }
            }
            Section("2 · Switch to it") {
                val seen = imeSeenActive()
                Text(
                    if (seen.isNotEmpty()) "MyWhisper is active ($seen)."
                    else "Pick MyWhisper from the list, then open any text field — the mic bar means it's active.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                }) { Text("Choose input method") }
            }
            Section("3 · Microphone") {
                Text(
                    if (micGranted.value) "Microphone granted."
                    else "Microphone is required for dictation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { micPerm.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone")
                }
            }
            Section("4 · Notifications") {
                Text(
                    "Shows the sync status while sync runs.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        toast("Notifications are always on below Android 13")
                    }
                }) {
                    Text("Allow notifications")
                }
            }
            Section("Mic button") {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                val mode = prefs.optString("mic_mode", "Smart")
                Text(
                    when (mode) {
                        "Push" -> "Hold to talk: press and hold, release to stop."
                        "Tap" -> "Toggle: tap to start, tap again to stop."
                        else -> "Smart: quick tap toggles, hold-and-release stops."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                DropDown("Behavior", mode, listOf("Smart", "Push", "Tap")) {
                    prefs.put("mic_mode", it)
                    s.put("preferences", prefs)
                    save()
                    reload()
                }
                val hotkeys = s.optJSONObject("hotkeys") ?: JSONObject()
                var tapMs by remember {
                    mutableStateOf(hotkeys.optLong("smart_tap_ms", 150L).toString())
                }
                OutlinedTextField(
                    value = tapMs,
                    onValueChange = { tapMs = it.filter(Char::isDigit).take(4) },
                    label = { Text("Smart tap ceiling (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    hotkeys.put("smart_tap_ms", tapMs.toLongOrNull() ?: 150L)
                    s.put("hotkeys", hotkeys)
                    save()
                }) { Text("Save tap timing") }
            }
            Section("Preferences") {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                DropDown(
                    "Theme", prefs.optString("theme", "System"),
                    listOf("System", "Light", "Dark"),
                ) {
                    prefs.put("theme", it)
                    s.put("preferences", prefs)
                    save()
                    reload()
                }
            }
        }
    }

    // -- Speech to Text -------------------------------------------------

    private fun sttObj(s: JSONObject): JSONObject =
        s.optJSONObject("stt") ?: JSONObject().also { s.put("stt", it) }

    private fun entriesOf(parent: JSONObject, key: String): JSONArray =
        parent.optJSONArray(key) ?: JSONArray().also { parent.put(key, it) }

    private fun entryList(arr: JSONArray): List<JSONObject> =
        List(arr.length()) { arr.optJSONObject(it) ?: JSONObject() }

    private fun moveEntry(arr: JSONArray, from: Int, to: Int) {
        if (from !in 0 until arr.length() || to !in 0 until arr.length() || from == to) return
        val item = arr.get(from)
        // Shift between from/to instead of remove+insert (same result,
        // keeps the code obvious).
        if (from < to) {
            for (i in from until to) arr.put(i, arr.get(i + 1))
            arr.put(to, item)
        } else {
            for (i in from downTo to + 1) arr.put(i, arr.get(i - 1))
            arr.put(to, item)
        }
    }

    private fun entrySummary(e: JSONObject): String {
        return when (e.optString("kind")) {
            "Local" -> "on-device · not available in this build"
            "SelfHosted" -> "${e.optString("url")} · ${e.optString("model")}"
            else -> "${e.optString("provider")} · ${e.optString("model")}"
        }
    }

    @Composable
    private fun SttScreen(s: JSONObject) {
        val stt = sttObj(s)
        var dialog by remember { mutableStateOf<JSONObject?>(null) }
        var localInfo by remember { mutableStateOf(false) }
        var version by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Chain (top = first tried)") {
                Text(
                    "Entries are tried top-down; the first success wins. " +
                        "On-device (Local) entries are skipped until on-device inference lands.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val entries = entriesOf(stt, "entries")
                ReorderableEntries(
                    items = entryList(entries),
                    idOf = { it.optString("id") },
                    onMove = { from, to ->
                        moveEntry(entries, from, to); save(); version++
                    },
                ) { e, handle ->
                    val isLocal = e.optString("kind") == "Local"
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (isLocal) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ) else CardDefaults.cardColors(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.padding(end = 4.dp)) { handle() }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.optString("name", "entry"))
                                Text(entrySummary(e), style = MaterialTheme.typography.bodySmall)
                                if (isLocal) {
                                    Text(
                                        "Coming later — needs no configuration.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!isLocal) {
                                Switch(
                                    checked = e.optBoolean("enabled", true),
                                    onCheckedChange = {
                                        e.put("enabled", it); save(); version++
                                    },
                                )
                                IconButton(onClick = { dialog = JSONObject(e.toString()) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                }
                            } else {
                                TextButton(onClick = { localInfo = true }) { Text("Why?") }
                            }
                            IconButton(onClick = {
                                val next = JSONArray()
                                for (j in 0 until entries.length()) {
                                    if (entries.optJSONObject(j)?.optString("id") != e.optString("id")) {
                                        next.put(entries.get(j))
                                    }
                                }
                                stt.put("entries", next); save(); version++
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
                Row {
                    OutlinedButton(onClick = {
                        dialog = newSttEntry("SelfHosted")
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Self-hosted")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { dialog = newSttEntry("Cloud") }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Cloud")
                    }
                }
            }
            Section("Capture") {
                var lang by remember { mutableStateOf(stt.optString("language", "auto")) }
                Text("Language", style = MaterialTheme.typography.bodyMedium)
                var langOpen by remember { mutableStateOf(false) }
                val langLabel = langs.firstOrNull { it.first == lang }?.second ?: lang
                OutlinedButton(onClick = { langOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(langLabel)
                }
                DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                    langs.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { langOpen = false; lang = code },
                        )
                    }
                }
                val vad = stt.optBoolean("vad_enabled", false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voice activity trim", modifier = Modifier.weight(1f))
                    Switch(checked = vad, onCheckedChange = {
                        stt.put("vad_enabled", it); save(); version++
                    })
                }
                var gate by remember { mutableStateOf(stt.optDouble("vad_threshold", 0.1).toFloat()) }
                Text("VAD gate: ${"%.2f".format(gate)}")
                Slider(
                    value = gate, onValueChange = { gate = it },
                    onValueChangeFinished = {
                        stt.put("vad_threshold", gate.toDouble()); save()
                    },
                    valueRange = 0f..0.5f,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    stt.put("language", lang.ifBlank { "auto" }); save()
                }) { Text("Save capture") }
            }
        }
        if (localInfo) {
            AlertDialog(
                onDismissRequest = { localInfo = false },
                title = { Text("On-device speech") },
                text = {
                    Text(
                        "The Local entry uses the live local configuration (models on this phone). " +
                            "On-device inference is not implemented in this build yet, so the entry " +
                            "is skipped. Add a Self-hosted or Cloud entry above it to dictate now — " +
                            "order decides priority.",
                    )
                },
                confirmButton = { TextButton(onClick = { localInfo = false }) { Text("Got it") } },
            )
        }
        dialog?.let { EntryDialog(it, "Speech service", onClose = { dialog = null }) { edited ->
            upsertEntry(stt, "entries", edited)
            save(); version++; dialog = null
        } }
    }

    private fun newSttEntry(kind: String): JSONObject = JSONObject()
        .put("id", "stt-${UUID.randomUUID()}")
        .put("name", if (kind == "Cloud") "Cloud" else "Server")
        .put("kind", kind)
        .put("enabled", true)
        .put("provider", "OpenAI")
        .put("url", "")
        .put("model", "")
        .put("api_key", "")
        .put("streaming", true)

    private fun upsertEntry(parent: JSONObject, key: String, edited: JSONObject) {
        val arr = entriesOf(parent, key)
        val id = edited.optString("id")
        val next = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val cur = arr.optJSONObject(i)
            if (cur != null && cur.optString("id") == id) {
                next.put(edited); replaced = true
            } else if (cur != null) {
                next.put(cur)
            }
        }
        if (!replaced) next.put(edited)
        parent.put(key, next)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EntryDialog(
        e: JSONObject,
        title: String,
        onClose: () -> Unit,
        onSave: (JSONObject) -> Unit,
    ) {
        var name by remember { mutableStateOf(e.optString("name")) }
        var url by remember { mutableStateOf(e.optString("url")) }
        var model by remember { mutableStateOf(e.optString("model")) }
        var provider by remember { mutableStateOf(e.optString("provider", "OpenAI")) }
        var key by remember { mutableStateOf(e.optString("api_key")) }
        var streaming by remember { mutableStateOf(e.optBoolean("streaming", true)) }
        var enabled by remember { mutableStateOf(e.optBoolean("enabled", true)) }
        var showKey by remember { mutableStateOf(false) }
        val kind = e.optString("kind")
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("$title · $kind") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    if (kind == "Cloud") {
                        DropDown("Provider", provider,
                            listOf("OpenAI", "Groq", "Mistral", "Deepgram", "Gemini")) {
                            provider = it
                        }
                        if (provider == "Gemini") {
                            Text(
                                "Gemini speech is not implemented in this build yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = {
                            Text(
                                if (kind == "Cloud") "Base URL (blank = vendor default)"
                                else "Server URL (http(s) or ws(s))",
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = model, onValueChange = { model = it },
                        label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("API key") },
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "hide" else "show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (kind == "SelfHosted") {
                        Text(
                            "Bearer token for reverse-proxy setups. Empty means no token.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (kind == "SelfHosted") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Streaming (ws servers)", modifier = Modifier.weight(1f))
                            Switch(checked = streaming, onCheckedChange = { streaming = it })
                        }
                        Text(
                            "http(s) URLs always use request/response; ws(s) URLs use the sherpa protocol.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    e.put("name", name).put("url", url.trim())
                        .put("model", model.trim()).put("provider", provider)
                        .put("api_key", key).put("streaming", streaming)
                        .put("enabled", enabled)
                    onSave(e)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
    }

    // -- Text Cleanup ---------------------------------------------------

    @Composable
    private fun CleanupScreen(s: JSONObject) {
        val lm = s.optJSONObject("language_models") ?: JSONObject().also {
            s.put("language_models", it)
        }
        var dialog by remember { mutableStateOf<JSONObject?>(null) }
        var localInfo by remember { mutableStateOf(false) }
        var version by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Cleanup") {
                val on = lm.optBoolean("cleanup_enabled", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Polish transcripts with an LLM", modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = {
                        lm.put("cleanup_enabled", it); save(); version++
                    })
                }
                // Effective prompt state: blank stored = built-in default.
                var defaultText by remember { mutableStateOf<String?>(null) }
                if (defaultText == null) {
                    LaunchedEffect(Unit) {
                        defaultText = try {
                            CoreBridge.defaultCleanupPrompt().getOrThrow()
                        } catch (_: Exception) {
                            ""
                        }
                    }
                }
                val stored = lm.optString("cleanup_prompt", "")
                val isDefault = stored.isBlank()
                val effective = if (isDefault) (defaultText ?: "") else stored
                var expanded by remember { mutableStateOf(false) }
                var editor by remember { mutableStateOf(false) }
                Text(
                    if (isDefault) "Using the built-in default prompt."
                    else "Using your customized prompt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val lines = effective.lines()
                        val shown = if (expanded) lines else lines.take(6)
                        Text(
                            shown.joinToString("\n").ifEmpty { "…" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (lines.size > 6) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Show less" else "Show all ${lines.size} lines")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = { editor = true }) {
                        Text(if (isDefault) "Customize" else "Edit")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !isDefault,
                        onClick = { lm.put("cleanup_prompt", ""); save(); version++ },
                    ) {
                        Text("Reset to default")
                    }
                }
                if (editor) {
                    var draft by remember { mutableStateOf(effective) }
                    AlertDialog(
                        onDismissRequest = { editor = false },
                        title = { Text("Cleanup prompt") },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    "Saved as your custom prompt. Reset returns to the built-in default.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 200.dp, max = 420.dp),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                // Unchanged-from-default stays blank (= default).
                                val def = defaultText ?: ""
                                lm.put(
                                    "cleanup_prompt",
                                    if (draft.trim() == def.trim() || draft.isBlank()) "" else draft,
                                )
                                save(); version++; editor = false
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { editor = false }) { Text("Cancel") }
                        },
                    )
                }
            }
            Section("Models (tried in order, first answer wins)") {
                Text(
                    "If the first enabled model fails or returns nothing, the next one is tried. " +
                        "Local entries have no engine yet (same as desktop) and are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val entries = entriesOf(lm, "entries")
                ReorderableEntries(
                    items = entryList(entries),
                    idOf = { it.optString("id") },
                    onMove = { from, to ->
                        moveEntry(entries, from, to); save(); version++
                    },
                ) { e, handle ->
                    val isLocal = e.optString("kind") == "Local"
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (isLocal) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ) else CardDefaults.cardColors(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.padding(end = 4.dp)) { handle() }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.optString("name", "model"))
                                Text(
                                    if (isLocal) "Local · no engine yet"
                                    else "${e.optString("kind")} · ${e.optString("model")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!isLocal) {
                                Switch(
                                    checked = e.optBoolean("enabled", true),
                                    onCheckedChange = {
                                        e.put("enabled", it); save(); version++
                                    },
                                )
                                IconButton(onClick = { dialog = JSONObject(e.toString()) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                }
                            } else {
                                TextButton(onClick = { localInfo = true }) { Text("Why?") }
                            }
                            IconButton(onClick = {
                                val next = JSONArray()
                                for (j in 0 until entries.length()) {
                                    if (entries.optJSONObject(j)?.optString("id") != e.optString("id")) {
                                        next.put(entries.get(j))
                                    }
                                }
                                lm.put("entries", next); save(); version++
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    dialog = JSONObject()
                        .put("id", "llm-${UUID.randomUUID()}")
                        .put("name", "Ollama")
                        .put("kind", "Ollama")
                        .put("enabled", true)
                        .put("url", "http://127.0.0.1:11434")
                        .put("model", "")
                        .put("provider", "OpenAI")
                        .put("api_key", "")
                        .put("keep_alive", "")
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add model")
                }
            }
        }
        if (localInfo) {
            AlertDialog(
                onDismissRequest = { localInfo = false },
                title = { Text("Local language model") },
                text = {
                    Text(
                        "Local LLM cleanup has no engine on desktop either — " +
                            "the entry is skipped and the chain moves on. " +
                            "Point at an Ollama server or a cloud model instead.",
                    )
                },
                confirmButton = { TextButton(onClick = { localInfo = false }) { Text("Got it") } },
            )
        }
        dialog?.let { d ->
            LlmDialog(d, onClose = { dialog = null }) { edited ->
                upsertEntry(lm, "entries", edited)
                save(); version++; dialog = null
            }
        }
    }

    @Composable
    private fun LlmDialog(
        e: JSONObject,
        onClose: () -> Unit,
        onSave: (JSONObject) -> Unit,
    ) {
        var name by remember { mutableStateOf(e.optString("name")) }
        var kind by remember { mutableStateOf(e.optString("kind", "Ollama")) }
        var url by remember { mutableStateOf(e.optString("url")) }
        var model by remember { mutableStateOf(e.optString("model")) }
        var provider by remember { mutableStateOf(e.optString("provider", "OpenAI")) }
        var key by remember { mutableStateOf(e.optString("api_key")) }
        var keepAlive by remember { mutableStateOf(e.optString("keep_alive", "")) }
        var thinking by remember {
            mutableStateOf(
                when {
                    e.isNull("thinking") -> "Default"
                    e.optBoolean("thinking") -> "On"
                    else -> "Off"
                },
            )
        }
        var enabled by remember { mutableStateOf(e.optBoolean("enabled", true)) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Language model · $kind") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    DropDown("Kind", kind, listOf("Ollama", "Local", "Cloud")) {
                        kind = it
                    }
                    if (kind == "Local") {
                        Text(
                            "Local has no engine yet (same as desktop) — the entry will be skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (kind == "Cloud") {
                        DropDown("Provider", provider,
                            listOf("Groq", "OpenRouter", "OpenAI", "Claude", "Google")) {
                            provider = it
                        }
                    }
                    if (kind != "Local") {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text(if (kind == "Cloud") "URL (blank = vendor default)" else "URL") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it },
                            label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == "Cloud") {
                        OutlinedTextField(value = key, onValueChange = { key = it },
                            label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == "Ollama") {
                        OutlinedTextField(value = key, onValueChange = { key = it },
                            label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                        Text(
                            "Bearer token for reverse-proxy setups. Empty means no token.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (kind == "Ollama") {
                        OutlinedTextField(value = keepAlive, onValueChange = { keepAlive = it },
                            label = { Text("Keep-alive (blank, -1, 0, 5m…)") },
                            modifier = Modifier.fillMaxWidth())
                        DropDown("Thinking", thinking, listOf("Default", "On", "Off")) {
                            thinking = it
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    e.put("name", name).put("kind", kind)
                        .put("url", url.trim()).put("model", model.trim())
                        .put("provider", provider).put("api_key", key)
                        .put("keep_alive", keepAlive.trim())
                        .put("enabled", enabled)
                    when (thinking) {
                        "On" -> e.put("thinking", true)
                        "Off" -> e.put("thinking", false)
                        else -> e.remove("thinking")
                    }
                    onSave(e)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
    }

    // -- Dictionary (searchable lazy lists: scales to big dicts) ----

    @Composable
    private fun DictionaryScreen(s: JSONObject) {
        val dict = s.optJSONObject("dictionary") ?: JSONObject().also {
            s.put("dictionary", it)
        }
        var version by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        var snipDialog by remember { mutableStateOf<JSONObject?>(null) }
        var snipIndex by remember { mutableIntStateOf(-1) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Words (${entriesOf(dict, "words").length()})") {
                Text(
                    "Exact spellings bias recognition.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val arr = entriesOf(dict, "words")
                var next by remember { mutableStateOf("") }
                var dup by remember { mutableStateOf(false) }
                var query by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = next, onValueChange = { next = it; dup = false },
                        label = { Text("New word") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val w = next.trim()
                        if (w.isNotBlank()) {
                            var exists = false
                            for (i in 0 until arr.length()) {
                                if (arr.optString(i).equals(w, ignoreCase = true)) exists = true
                            }
                            if (exists) {
                                dup = true
                            } else {
                                arr.put(w); next = ""
                                dict.put("words", arr); save(); version++
                            }
                        }
                    }) { Text("Add") }
                }
                if (dup) {
                    Text(
                        "That word is already listed.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search words") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                val shown = (0 until arr.length())
                    .map { arr.optString(it) }
                    .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                if (arr.length() == 0) {
                    Text(
                        "No words yet — add names and terms the recognizer mishears.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (shown.isEmpty()) {
                    Text("No matches.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    ) {
                        items(shown, key = { it }) { w ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(w, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val n = JSONArray()
                                    for (j in 0 until arr.length()) {
                                        if (arr.optString(j) != w) n.put(arr.get(j))
                                    }
                                    dict.put("words", n); save(); version++
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
            Section("Snippets (${entriesOf(dict, "snippets").length()})") {
                Text(
                    "Say the trigger, get the replacement.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val arr = entriesOf(dict, "snippets")
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search snippets") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                val shown = (0 until arr.length())
                    .mapNotNull { i -> arr.optJSONObject(i)?.let { i to it } }
                    .filter { (_, sn) ->
                        query.isBlank() ||
                            sn.optString("trigger").contains(query, ignoreCase = true) ||
                            sn.optString("replacement").contains(query, ignoreCase = true)
                    }
                if (arr.length() == 0) {
                    Text("No snippets yet.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                } else if (shown.isEmpty()) {
                    Text("No matches.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        items(shown, key = { (_, sn) -> sn.optString("trigger") + sn.optString("replacement") }) { (i, sn) ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sn.optString("trigger"))
                                        Text(
                                            "→ ${sn.optString("replacement")}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconButton(onClick = {
                                        snipIndex = i
                                        snipDialog = JSONObject(sn.toString())
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                    }
                                    IconButton(onClick = {
                                        val n = JSONArray()
                                        for (j in 0 until arr.length()) {
                                            if (j != i) n.put(arr.get(j))
                                        }
                                        dict.put("snippets", n); save(); version++
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    snipIndex = -1
                    snipDialog = JSONObject().put("trigger", "").put("replacement", "")
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add snippet")
                }
            }
        }
        snipDialog?.let { d ->
            var trig by remember { mutableStateOf(d.optString("trigger")) }
            var repl by remember { mutableStateOf(d.optString("replacement")) }
            AlertDialog(
                onDismissRequest = { snipDialog = null },
                title = { Text(if (snipIndex < 0) "Add snippet" else "Edit snippet") },
                text = {
                    Column {
                        OutlinedTextField(value = trig, onValueChange = { trig = it },
                            label = { Text("Trigger (comma-separated allowed)") },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = repl, onValueChange = { repl = it },
                            label = { Text("Replacement") },
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (trig.isNotBlank() && repl.isNotBlank()) {
                            val arr = entriesOf(dict, "snippets")
                            val obj = JSONObject()
                                .put("trigger", trig.trim()).put("replacement", repl)
                            if (snipIndex in 0 until arr.length()) {
                                arr.put(snipIndex, obj)
                            } else {
                                arr.put(obj)
                            }
                            dict.put("snippets", arr); save()
                            version++
                        }
                        snipDialog = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { snipDialog = null }) { Text("Cancel") } },
            )
        }
    }

    // -- Activity (mirrors desktop insights: stat cards + heatmap) ----

    private data class Day(val dayStart: Long, val words: Int, val utterances: Int)

    /** Desktop compact word counts: 1500 -> 1.5K. */
    private fun compactWords(n: Long): String {
        if (n < 1000) return n.toString()
        val k = n / 1000.0
        return if (k >= 100.0) "${k.toInt()}K" else "%.1f".format(k) + "K"
    }

    @Composable
    private fun ActivityScreen() {
        var stats by remember { mutableStateOf<JSONObject?>(null) }
        var days by remember { mutableStateOf<List<Day>>(emptyList()) }
        var err by remember { mutableStateOf("") }
        var version by remember { mutableIntStateOf(0) }
        LaunchedEffect(version) {
            try {
                stats = CoreBridge.usageStats().getOrThrow()
                val arr = CoreBridge.dailyActivity(200).getOrThrow()
                days = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Day(o.getLong("dayStart"), o.getInt("words"), o.getInt("utterances"))
                }
            } catch (e: Exception) {
                err = e.message ?: "failed"
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Your saved dictations, calculated on this device. Deleting history also updates these totals.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (err.isNotEmpty()) {
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val u = stats?.optLong("dictations", 0L) ?: 0L
            val w = stats?.optLong("wordsSpoken", 0L) ?: 0L
            val wpm = stats?.optDouble("wordsPerMinute", 0.0) ?: 0.0
            val streak = stats?.optInt("streakDays", 0) ?: 0
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        "Words spoken", compactWords(w), "Saved history",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        "Words per minute", if (wpm > 0) "${wpm.toInt()}" else "–",
                        "Dictations with a recorded duration",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        "Dictations", "$u", "Saved history",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        "Current streak",
                        if (streak == 1) "1 day" else "$streak days",
                        "Consecutive active days",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Section("Speaking activity") {
                if (days.isEmpty()) {
                    Text("Nothing yet — dictate something first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    var tapped by remember { mutableStateOf<Day?>(null) }
                    Heatmap(days, onDayTap = { tapped = it })
                    tapped?.let { d ->
                        val date = try {
                            java.time.LocalDate.ofEpochDay(d.dayStart / 86400)
                                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
                        } catch (_: Exception) {
                            ""
                        }
                        AlertDialog(
                            onDismissRequest = { tapped = null },
                            title = { Text(date.ifEmpty { "Day" }) },
                            text = {
                                Text("${d.words} ${if (d.words == 1) "word" else "words"} · " +
                                    "${d.utterances} ${if (d.utterances == 1) "dictation" else "dictations"}")
                            },
                            confirmButton = {
                                TextButton(onClick = { tapped = null }) { Text("Close") }
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { version++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh")
                    }
                }
            }
        }
    }

    @Composable
    private fun StatCard(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
        Card(modifier = modifier) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(caption, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    /** GitHub-style level from a real word count (desktop thresholds). */
    private fun heatLevel(words: Int): Int = when {
        words <= 0 -> 0
        words < 50 -> 1
        words < 200 -> 2
        else -> 3
    }

    /**
     * 24-week activity heatmap like desktop: columns oldest → newest,
     * rows Sunday → Saturday, month labels, less → more legend.
     * Cells are tappable and report their day via [onDayTap].
     */
    @Composable
    private fun Heatmap(days: List<Day>, onDayTap: (Day) -> Unit) {
        val byDay = days.associate { (it.dayStart / 86400) to it }
        val weeks = 24
        val today = java.time.LocalDate.now().toEpochDay()
        // Saturday-anchored grid, same math as desktop cell_epoch_day.
        val saturday = today + (6 - Math.floorMod(today + 4, 7))
        data class Cell(val epoch: Long, val level: Int)
        val grid = List(weeks) { week ->
            List(7) { day ->
                val epoch = saturday - ((weeks - 1 - week) * 7 + (6 - day))
                Cell(epoch, heatLevel(byDay[epoch]?.words ?: 0))
            }
        }
        val empty = MaterialTheme.colorScheme.surfaceVariant
        val full = MaterialTheme.colorScheme.primary
        val shades = listOf(
            empty,
            full.copy(alpha = 0.35f),
            full.copy(alpha = 0.65f),
            full,
        )
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val gapPx = with(density) { 3.dp.toPx() }
            val cellPx = with(density) {
                ((maxWidth.toPx() - gapPx * (weeks - 1)) / weeks)
                    .coerceIn(8.dp.toPx(), 64f)
            }
            val labelH = with(density) { 18.dp.toPx() }
            val totalH = with(density) {
                ((cellPx + gapPx) * 7 + labelH + 8.dp.toPx()).toDp()
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalH)
                    .pointerInput(grid) {
                        detectTapGestures { offset ->
                            val relY = offset.y - labelH
                            if (relY < 0) return@detectTapGestures
                            val week = (offset.x / (cellPx + gapPx)).toInt()
                            val day = (relY / (cellPx + gapPx)).toInt()
                            if (week in 0 until weeks && day in 0..6) {
                                val epoch = grid[week][day].epoch
                                onDayTap(
                                    byDay[epoch]
                                        ?: Day(epoch * 86400, 0, 0),
                                )
                            }
                        }
                    },
            ) {
                // Month labels on the first column where the month changes.
                // Like desktop, skip a cramped first label and any label
                // that would overlap the previous one.
                var prevMonth = -1
                var lastLabelEnd = -1f
                for (week in 0 until weeks) {
                    val weekMonth = java.time.LocalDate.ofEpochDay(grid[week][0].epoch).monthValue
                    val nextMonth = if (week + 1 < weeks) {
                        java.time.LocalDate.ofEpochDay(grid[week + 1][0].epoch).monthValue
                    } else weekMonth
                    if (week == 0 && nextMonth != weekMonth) {
                        prevMonth = weekMonth
                        continue
                    }
                    if (week == 0 || weekMonth != prevMonth) {
                        val name = java.time.LocalDate.ofEpochDay(grid[week][0].epoch)
                            .month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                        val x = week * (cellPx + gapPx)
                        val layout = measurer.measure(name, labelStyle)
                        if (x >= lastLabelEnd) {
                            drawText(layout, topLeft = Offset(x, 0f))
                            lastLabelEnd = x + layout.size.width + gapPx * 2
                        }
                    }
                    prevMonth = weekMonth
                }
                for (week in 0 until weeks) {
                    for (day in 0 until 7) {
                        val c = grid[week][day]
                        drawRoundRect(
                            color = shades[c.level],
                            topLeft = Offset(
                                week * (cellPx + gapPx),
                                labelH + day * (cellPx + gapPx),
                            ),
                            size = androidx.compose.ui.geometry.Size(cellPx, cellPx),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                cellPx * 0.2f, cellPx * 0.2f,
                            ),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Less", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            for (l in 0..3) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(12.dp)
                        .background(shades[l], androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text("More", style = MaterialTheme.typography.bodySmall)
        }
    }

    // -- History + privacy ----------------------------------------------

    private data class Hist(
        val uuid: String,
        val text: String,
        val date: String,
        val words: Int,
    )

    @Composable
    private fun HistoryScreen(s: JSONObject) {
        var items by remember { mutableStateOf(loadHist()) }
        var version by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        Column(modifier = Modifier.fillMaxSize()) {
            var privOpen by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Privacy & retention",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { privOpen = !privOpen }) {
                            Text(if (privOpen) "Hide" else "Show")
                        }
                    }
                    if (privOpen) {
                        Spacer(Modifier.height(8.dp))
                        val priv = s.optJSONObject("privacy") ?: JSONObject().also {
                            s.put("privacy", it)
                        }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep history")
                            Text(
                                "Off = dictate without storing.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = priv.optBoolean("history_enabled", true),
                            onCheckedChange = {
                                priv.put("history_enabled", it); save(); version++
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep cancelled dictations")
                            Text(
                                "Store rows for cancelled recordings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = priv.optBoolean("save_discarded_transcriptions", false),
                            onCheckedChange = {
                                priv.put("save_discarded_transcriptions", it); save(); version++
                            },
                        )
                    }
                    var daysStr by remember {
                        mutableStateOf(priv.optInt("transcript_auto_delete_days", 0).toString())
                    }
                    OutlinedTextField(
                        value = daysStr,
                        onValueChange = { daysStr = it.filter(Char::isDigit).take(4) },
                        label = { Text("Auto-delete transcripts after N days (0 = keep)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = {
                            priv.put("transcript_auto_delete_days", daysStr.toIntOrNull() ?: 0)
                            save()
                        }) { Text("Save") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val days = daysStr.toIntOrNull() ?: 0
                            if (days > 0) {
                                workers.execute {
                                    try {
                                        val n = CoreBridge.purgeHistory(days).getOrThrow()
                                        bgToast("deleted $n old entries")
                                    } catch (e: Exception) {
                                        bgToast("purge failed: ${e.message}")
                                    }
                                    runOnUiThread { items = loadHist() }
                                }
                            } else {
                                toast("set N days first")
                            }
                        }) { Text("Delete older now") }
                    }
                }
            }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${items.size} entries", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = { items = loadHist() }) { Text("Refresh") }
                    TextButton(onClick = {
                        try {
                            CoreBridge.dataOf(CoreBridge.nativeClearHistory())
                            items = loadHist()
                        } catch (e: Exception) {
                            toast("clear failed: ${e.message}")
                        }
                    }) { Text("Clear") }
                }
            }
            var histQuery by remember { mutableStateOf("") }
            OutlinedTextField(
                value = histQuery, onValueChange = { histQuery = it },
                label = { Text("Search transcripts") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                singleLine = true,
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items.filter { h ->
                        histQuery.isBlank() || h.text.contains(histQuery, ignoreCase = true)
                    },
                    key = { it.uuid },
                ) { h ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(h.text)
                                Text(
                                    "${h.date} · ${h.words} words",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = {
                                try {
                                    CoreBridge.dataOf(CoreBridge.nativeDeleteHistory(h.uuid))
                                    items = loadHist()
                                } catch (e: Exception) {
                                    toast("delete failed: ${e.message}")
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadHist(): List<Hist> {
        return try {
            val raw = CoreBridge.nativeGetHistory(200)
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Hist(
                    o.optString("uuid"),
                    o.optString("text"),
                    o.optString("dateLabel"),
                    o.optInt("wordCount"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -- Sync -----------------------------------------------------------

    private fun acquireMulticast() {
        try {
            if (multicastLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("mywhisper-sync").apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld != true) multicastLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticast() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun agoText(epochSecs: Long): String {
        if (epochSecs <= 0) return "never"
        val dt = System.currentTimeMillis() / 1000 - epochSecs
        return when {
            dt < 60 -> "just now"
            dt < 3600 -> "${dt / 60} min ago"
            dt < 86400 -> "${dt / 3600} h ago"
            else -> "${dt / 86400} d ago"
        }
    }

    @Composable
    private fun SyncScreen(s: JSONObject) {
        val sync = s.optJSONObject("sync") ?: JSONObject().also { s.put("sync", it) }
        var status by remember { mutableStateOf<JSONObject?>(null) }
        var peers by remember { mutableStateOf(loadPeers()) }
        var version by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf(mapOf<String, String>()) }
        var displayName by remember { mutableStateOf("") }
        var addOpen by remember { mutableStateOf(false) }
        fun refresh() {
            try {
                status = CoreBridge.syncStatus().getOrThrow()
            } catch (_: Exception) {
            }
            peers = loadPeers()
            displayName = CoreBridge.displayName()
            version++
        }
        fun note(id: String, text: String) {
            notes = notes + (id to text)
        }
        @Suppress("UNUSED_EXPRESSION") version
        LaunchedEffect(Unit) {
            refresh()
            while (true) {
                delay(5000)
                try {
                    status = CoreBridge.syncStatus().getOrThrow()
                    peers = loadPeers()
                } catch (_: Exception) {
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("This device") {
                val running = status?.optBoolean("running", false) == true
                Text(
                    if (running) {
                        val shown = displayName.ifEmpty { status?.optString("nodeId", "")?.take(12) + "…" }
                        "Sync running · $shown"
                    } else {
                        "Sync stopped."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                val lan = status?.optString("lanIp", "") ?: ""
                if (lan.isNotEmpty()) {
                    Text("LAN address: $lan", style = MaterialTheme.typography.bodySmall)
                }
                var name by remember { mutableStateOf(sync.optString("device_name", "")) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Device name (blank = ${displayName.ifEmpty { "generated" }})") },
                    modifier = Modifier.fillMaxWidth(),
                )
                var portStr by remember {
                    mutableStateOf(sync.optInt("listen_port", 42973).toString())
                }
                OutlinedTextField(
                    value = portStr, onValueChange = { portStr = it.filter(Char::isDigit).take(5) },
                    label = { Text("Listen port (0 = no incoming)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val adv = sync.optBoolean("advertise", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Advertise on LAN (mDNS)")
                        Text(
                            "Off = invisible, still sees others.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = adv, onCheckedChange = {
                        sync.put("advertise", it); save(); version++
                        restartSyncIfRunning(running)
                    })
                }
                val enabled = sync.optBoolean("enabled", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync enabled", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = {
                        sync.put("enabled", it); save(); version++
                        if (!it && running) {
                            SyncService.stop(this@MainActivity)
                            CoreBridge.syncStop()
                            releaseMulticast()
                            refresh()
                        } else {
                            restartSyncIfRunning(running && it)
                        }
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start on boot")
                        Text(
                            "Relaunch sync after reboot when enabled.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = sync.optBoolean("start_on_boot", true),
                        onCheckedChange = {
                            sync.put("start_on_boot", it); save(); version++
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = {
                        sync.put("device_name", name.trim())
                        sync.put("listen_port", portStr.toIntOrNull() ?: 42973)
                        save()
                        restartSyncIfRunning(running)
                    }) { Text("Save") }
                    Spacer(Modifier.width(8.dp))
                    if (!running) {
                        Button(onClick = {
                            acquireMulticast()
                            // Foreground service keeps the listener,
                            // discovery and rounds alive + shows status.
                            SyncService.start(this@MainActivity)
                            workers.execute {
                                // Fallback if the service path is slow.
                                try {
                                    CoreBridge.syncStart()
                                } catch (_: Exception) {
                                }
                                runOnUiThread { refresh() }
                            }
                        }) { Text("Start") }
                    } else {
                        OutlinedButton(onClick = {
                            SyncService.stop(this@MainActivity)
                            CoreBridge.syncStop()
                            releaseMulticast()
                            refresh()
                        }) { Text("Stop") }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = running && busy.isEmpty(),
                        onClick = {
                            busy = "syncing…"
                            workers.execute {
                                try {
                                    val lines = CoreBridge.syncAllNow().getOrThrow()
                                    bgToast(lines.firstOrNull() ?: "done")
                                } catch (e: Exception) {
                                    bgToast("sync failed: ${e.message}")
                                }
                                busy = ""
                                runOnUiThread { refresh() }
                            }
                        },
                    ) { Text(if (busy.isEmpty()) "Sync now" else busy) }
                }
                val reports = status?.optJSONArray("reports")
                if (reports != null && reports.length() > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (0 until reports.length()).joinToString("\n") { reports.optString(it) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val trusted = peers.filter { it.optBoolean("trusted") }
            val requests = peers.filter { it.optBoolean("pending") }
            val nearby = peers.filter {
                !it.optBoolean("trusted") && !it.optBoolean("pending") && it.optBoolean("discovered")
            }
            Section("Trusted (${trusted.size})") {
                if (trusted.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                trusted.forEach { p ->
                    val id = p.optString("nodeId")
                    PeerRow(
                        p,
                        note = notes[id],
                        lastSync = "last sync ${agoText(p.optLong("lastSync", 0))}",
                        onSync = {
                            busy = "syncing…"
                            workers.execute {
                                try {
                                    val msg = CoreBridge.syncNow(id).getOrThrow()
                                    runOnUiThread { note(id, msg) }
                                } catch (e: Exception) {
                                    runOnUiThread { note(id, "sync failed: ${e.message}") }
                                }
                                busy = ""
                                runOnUiThread { refresh() }
                            }
                        },
                        onRemove = {
                            workers.execute {
                                try {
                                    CoreBridge.syncTrust(id, false).getOrThrow()
                                } catch (e: Exception) {
                                    bgToast("remove failed: ${e.message}")
                                }
                                runOnUiThread { refresh() }
                            }
                        },
                    )
                }
            }
            Section("Pairing requests (${requests.size})") {
                if (requests.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
                requests.forEach { p ->
                    val id = p.optString("nodeId")
                    val nm = p.optString("name").ifEmpty { id.take(12) + "…" }
                    PeerRow(
                        p,
                        note = notes[id],
                        onAccept = { acceptPeer(id, nm, { note(id, it) }) { refresh() } },
                        onRemove = {
                            workers.execute {
                                try {
                                    CoreBridge.syncTrust(id, false).getOrThrow()
                                } catch (_: Exception) {
                                }
                                runOnUiThread { refresh() }
                            }
                        },
                    )
                }
            }
            Section("Nearby (${nearby.size})") {
                Text(
                    "Discovered on the LAN. Accept to trust and sync " +
                        "(works with desktops and other phones).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (nearby.isEmpty()) Text("Nobody around.", style = MaterialTheme.typography.bodySmall)
                nearby.forEach { p ->
                    val id = p.optString("nodeId")
                    val nm = p.optString("name").ifEmpty { id.take(12) + "…" }
                    PeerRow(
                        p,
                        note = notes[id],
                        onAccept = { acceptPeer(id, nm, { note(id, it) }) { refresh() } },
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
            }
            Section("Add device manually") {
                Text(
                    "Copy the Device ID from MyWhisper on your other device. " +
                        "Send a pairing request, then accept it there. " +
                        "Both devices must be reachable.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { addOpen = true }) { Text("Add by ID") }
                notes["__add"]?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (addOpen) {
            var id by remember { mutableStateOf("") }
            var addr by remember { mutableStateOf("") }
            var nm by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { addOpen = false },
                title = { Text("Add device") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = id, onValueChange = { id = it },
                            label = { Text("Other device's ID (64 hex chars)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = addr, onValueChange = { addr = it },
                            label = { Text("Address override, e.g. 192.168.1.31:42973 (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = nm, onValueChange = { nm = it },
                            label = { Text("Name (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        workers.execute {
                            try {
                                val msg = CoreBridge.syncAdd(id, addr, nm).getOrThrow()
                                runOnUiThread { notes = notes + ("__add" to msg) }
                            } catch (e: Exception) {
                                runOnUiThread { notes = notes + ("__add" to "failed: ${e.message}") }
                            }
                            runOnUiThread { refresh(); addOpen = false }
                        }
                    }) { Text("Send pairing request") }
                },
                dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } },
            )
        }
    }

    /** Trust then immediately run one round, reporting the outcome inline. */
    private fun acceptPeer(id: String, name: String, setNote: (String) -> Unit, refresh: () -> Unit) {
        workers.execute {
            try {
                CoreBridge.syncTrust(id, true).getOrThrow()
            } catch (e: Exception) {
                runOnUiThread { setNote("accept failed: ${e.message}"); refresh() }
                return@execute
            }
            try {
                val msg = CoreBridge.syncNow(id).getOrThrow()
                runOnUiThread { setNote(msg) }
            } catch (e: Exception) {
                // The usual case: we dialed, they recorded us as
                // pending, and rejected until they accept there.
                runOnUiThread {
                    setNote("waiting for $name to accept — ask them to accept on the other device")
                }
            }
            runOnUiThread { refresh() }
        }
    }

    /** Apply Name/Port/Advertise/Enabled edits without a manual restart. */
    private fun restartSyncIfRunning(running: Boolean) {
        if (!running) {
            toast("saved")
            return
        }
        try {
            SyncService.stop(this)
        } catch (_: Exception) {
        }
        try {
            CoreBridge.syncStop()
        } catch (_: Exception) {
        }
        acquireMulticast()
        SyncService.start(this)
        toast("saved — sync restarted")
    }

    private fun loadPeers(): List<JSONObject> {
        return try {
            val arr = JSONArray(CoreBridge.syncPeersRaw())
            List(arr.length()) { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Composable
    private fun PeerRow(
        p: JSONObject,
        note: String? = null,
        lastSync: String? = null,
        onSync: (() -> Unit)? = null,
        onAccept: (() -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.optString("name", "?"))
                        val addrs = p.optJSONArray("addresses")?.let { a ->
                            List(a.length()) { a.optString(it) }
                        } ?: emptyList()
                        val disc = p.optJSONArray("discoveredAddrs")?.let { a ->
                            List(a.length()) { a.optString(it) }
                        } ?: emptyList()
                        Text(
                            (addrs + disc).distinct().take(2).joinToString(" · ").ifEmpty { "no address yet" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (lastSync != null) {
                            Text(lastSync, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    onSync?.let { TextButton(onClick = it) { Text("Sync") } }
                    onAccept?.let { TextButton(onClick = it) { Text("Accept") } }
                    onRemove?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                }
                if (!note.isNullOrEmpty()) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
