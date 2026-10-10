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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
     * Chain list with a left grab handle: long-press and drag to
     * reorder (standard reorder UX). Calls [onMove] with the JSON
     * array indices; the caller persists.
     */
    @Composable
    private fun ReorderableEntries(
        items: List<JSONObject>,
        idOf: (JSONObject) -> String,
        onMove: (from: Int, to: Int) -> Unit,
        row: @Composable (entry: JSONObject, handle: @Composable () -> Unit) -> Unit,
    ) {
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragIdx by remember { mutableIntStateOf(-1) }
        var accumulated by remember { mutableFloatStateOf(0f) }
        val tops = remember { mutableMapOf<String, Float>() }
        val heights = remember { mutableMapOf<String, Float>() }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            items(items, key = { idOf(it) }) { item ->
                val id = idOf(item)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            tops[id] = it.positionInParent().y
                            heights[id] = it.size.height.toFloat()
                        }
                        .background(
                            if (dragging == id) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        ),
                ) {
                    row(item) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = id
                                        dragIdx = items.indexOfFirst { idOf(it) == id }
                                        accumulated = 0f
                                    },
                                    onDragEnd = { dragging = null; dragIdx = -1 },
                                    onDragCancel = { dragging = null; dragIdx = -1 },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (dragging == null) return@detectDragGesturesAfterLongPress
                                        accumulated += dragAmount.y
                                        val h = heights[id] ?: return@detectDragGesturesAfterLongPress
                                        if (h <= 0f) return@detectDragGesturesAfterLongPress
                                        val center = (tops[id] ?: 0f) + h / 2f + accumulated
                                        val ordered = tops.entries.sortedBy { it.value }
                                        var target = dragIdx
                                        for ((i, e) in ordered.withIndex()) {
                                            val eh = heights[e.key] ?: h
                                            if (center >= e.value && center <= e.value + eh) {
                                                target = i
                                                break
                                            }
                                            if (center < e.value) {
                                                target = i
                                                break
                                            }
                                            target = i
                                        }
                                        if (target in items.indices && target != dragIdx && dragIdx >= 0) {
                                            onMove(dragIdx, target)
                                            dragIdx = target
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
        var showDefault by remember { mutableStateOf(false) }
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
                var prompt by remember { mutableStateOf(lm.optString("cleanup_prompt", "")) }
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("Custom prompt (blank = built-in default)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = { lm.put("cleanup_prompt", prompt); save() }) {
                        Text("Save prompt")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { prompt = ""; lm.put("cleanup_prompt", ""); save() }) {
                        Text("Reset to default")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showDefault = true }) { Text("View default") }
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
        if (showDefault) {
            var text by remember { mutableStateOf("loading…") }
            LaunchedEffect(Unit) {
                text = try {
                    CoreBridge.defaultCleanupPrompt().getOrThrow()
                } catch (e: Exception) {
                    "unavailable: ${e.message}"
                }
            }
            AlertDialog(
                onDismissRequest = { showDefault = false },
                title = { Text("Built-in cleanup prompt") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { showDefault = false }) { Text("Close") } },
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

    // -- Dictionary -----------------------------------------------------

    @OptIn(ExperimentalLayoutApi::class)
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
            Section("Words (exact spellings bias recognition)") {
                val arr = entriesOf(dict, "words")
                var next by remember { mutableStateOf("") }
                var dup by remember { mutableStateOf(false) }
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
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until arr.length()) {
                        val w = arr.optString(i)
                        AssistChip(
                            onClick = {},
                            label = { Text(w) },
                            trailingIcon = {
                                TextButton(onClick = {
                                    val n = JSONArray()
                                    for (j in 0 until arr.length()) {
                                        if (j != i) n.put(arr.get(j))
                                    }
                                    dict.put("words", n); save(); version++
                                }) { Text("×") }
                            },
                        )
                    }
                }
                if (arr.length() == 0) {
                    Text(
                        "No words yet — add names and terms the recognizer mishears.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Section("Snippets (say the trigger, get the replacement)") {
                val arr = entriesOf(dict, "snippets")
                for (i in 0 until arr.length()) {
                    val sn = arr.optJSONObject(i) ?: continue
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
                if (arr.length() == 0) {
                    Text(
                        "No snippets yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
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

    // -- Activity -------------------------------------------------------

    private data class Day(val dayStart: Long, val words: Int, val utterances: Int)

    @Composable
    private fun ActivityScreen() {
        var stats by remember { mutableStateOf<JSONObject?>(null) }
        var days by remember { mutableStateOf<List<Day>>(emptyList()) }
        var err by remember { mutableStateOf("") }
        var version by remember { mutableIntStateOf(0) }
        LaunchedEffect(version) {
            try {
                stats = CoreBridge.usageStats().getOrThrow()
                val arr = CoreBridge.dailyActivity(30).getOrThrow()
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
            Section("Totals") {
                if (err.isNotEmpty()) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
                val u = stats?.optLong("dictations", 0L) ?: 0L
                val w = stats?.optLong("wordsSpoken", 0L) ?: 0L
                val wpm = stats?.optDouble("wordsPerMinute", 0.0) ?: 0.0
                val streak = stats?.optInt("streakDays", 0) ?: 0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("$u", "dictations")
                    Stat("$w", "words")
                    Stat(if (wpm > 0) "${wpm.toInt()}" else "–", "wpm")
                    Stat("$streak", "day streak")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { version++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
            Section("Last 14 days (words)") {
                if (days.isEmpty()) {
                    Text("Nothing yet — dictate something first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    DayBars(days.takeLast(14))
                }
            }
        }
    }

    @Composable
    private fun Stat(value: String, label: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun DayBars(days: List<Day>) {
        val max = (days.maxOfOrNull { it.words } ?: 0).coerceAtLeast(1)
        val bar = MaterialTheme.colorScheme.primary
        val axis = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(top = 8.dp),
        ) {
            val n = days.size
            if (n == 0) return@Canvas
            val gap = size.width / (n * 2f)
            val bw = gap * 1.2f
            days.forEachIndexed { i, d ->
                val x = gap + i * 2 * gap
                val h = (d.words.toFloat() / max) * (size.height - 24f)
                drawRect(
                    color = bar,
                    topLeft = Offset(x - bw / 2f, size.height - 20f - h),
                    size = androidx.compose.ui.geometry.Size(bw, h),
                )
            }
            drawLine(
                color = axis,
                start = Offset(0f, size.height - 20f),
                end = Offset(size.width, size.height - 20f),
                strokeWidth = 2f,
            )
        }
        val first = days.firstOrNull()
        val last = days.lastOrNull()
        if (first != null && last != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dayLabel(first.dayStart), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${days.sumOf { it.words }} words · ${days.sumOf { it.utterances }} dictations",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(dayLabel(last.dayStart), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun dayLabel(epochSec: Long): String {
        return try {
            val f = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            f.format(java.util.Date(epochSec * 1000))
        } catch (_: Exception) {
            ""
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
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Privacy & retention", style = MaterialTheme.typography.titleMedium)
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.uuid }) { h ->
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

    @Composable
    private fun SyncScreen(s: JSONObject) {
        val sync = s.optJSONObject("sync") ?: JSONObject().also { s.put("sync", it) }
        var status by remember { mutableStateOf<JSONObject?>(null) }
        var peers by remember { mutableStateOf(loadPeers()) }
        var version by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf("") }
        fun refresh() {
            try {
                status = CoreBridge.syncStatus().getOrThrow()
            } catch (_: Exception) {
            }
            peers = loadPeers()
            version++
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
                        "Sync running · node ${status?.optString("nodeId", "")?.take(12)}…"
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
                    label = { Text("Device name (shown to peers)") },
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
                    })
                }
                val enabled = sync.optBoolean("enabled", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sync enabled", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = {
                        sync.put("enabled", it); save(); version++
                    })
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = {
                        sync.put("device_name", name.trim())
                        sync.put("listen_port", portStr.toIntOrNull() ?: 42973)
                        save()
                        toast("saved — restart sync to apply")
                    }) { Text("Save") }
                    Spacer(Modifier.width(8.dp))
                    if (!running) {
                        Button(onClick = {
                            acquireMulticast()
                            workers.execute {
                                try {
                                    val msg = CoreBridge.syncStart().getOrThrow()
                                    bgToast(msg)
                                } catch (e: Exception) {
                                    bgToast("start failed: ${e.message}")
                                }
                                runOnUiThread { refresh() }
                            }
                        }) { Text("Start") }
                    } else {
                        OutlinedButton(onClick = {
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
                    PeerRow(p, onSync = {
                        busy = "syncing…"
                        workers.execute {
                            try {
                                val msg = CoreBridge.syncNow(p.optString("nodeId")).getOrThrow()
                                bgToast(msg)
                            } catch (e: Exception) {
                                bgToast("sync failed: ${e.message}")
                            }
                            busy = ""
                            runOnUiThread { refresh() }
                        }
                    }, onRemove = {
                        workers.execute {
                            try {
                                CoreBridge.syncTrust(p.optString("nodeId"), false).getOrThrow()
                            } catch (e: Exception) {
                                bgToast("remove failed: ${e.message}")
                            }
                            runOnUiThread { refresh() }
                        }
                    })
                }
            }
            Section("Pairing requests (${requests.size})") {
                if (requests.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
                requests.forEach { p ->
                    PeerRow(p, onAccept = {
                        workers.execute {
                            try {
                                val msg = CoreBridge.syncTrust(p.optString("nodeId"), true).getOrThrow()
                                bgToast(msg)
                            } catch (e: Exception) {
                                bgToast("accept failed: ${e.message}")
                            }
                            runOnUiThread { refresh() }
                        }
                    }, onRemove = {
                        workers.execute {
                            try {
                                CoreBridge.syncTrust(p.optString("nodeId"), false).getOrThrow()
                            } catch (_: Exception) {
                            }
                            runOnUiThread { refresh() }
                        }
                    })
                }
            }
            Section("Nearby (${nearby.size})") {
                Text(
                    "Discovered on the LAN. Accept to trust and sync (same protocol as desktop).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (nearby.isEmpty()) Text("Nobody around.", style = MaterialTheme.typography.bodySmall)
                nearby.forEach { p ->
                    PeerRow(p, onAccept = {
                        workers.execute {
                            try {
                                val msg = CoreBridge.syncTrust(p.optString("nodeId"), true).getOrThrow()
                                bgToast(msg)
                            } catch (e: Exception) {
                                bgToast("accept failed: ${e.message}")
                            }
                            runOnUiThread { refresh() }
                        }
                    })
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
            }
        }
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
        onSync: (() -> Unit)? = null,
        onAccept: (() -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
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
                }
                onSync?.let { TextButton(onClick = it) { Text("Sync") } }
                onAccept?.let { TextButton(onClick = it) { Text("Accept") } }
                onRemove?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}
