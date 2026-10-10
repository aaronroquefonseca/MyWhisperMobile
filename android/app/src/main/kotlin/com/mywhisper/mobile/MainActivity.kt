package com.mywhisper.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Config app mirroring the desktop Settings screens (server-relevant
 * subset): Setup (IME enable + permissions + preferences), Speech to
 * Text (server/cloud chain), Text Cleanup (LLM chain), Dictionary,
 * History. All edits round-trip through the core's settings JSON, so the
 * schema stays identical to desktop.
 */
class MainActivity : ComponentActivity() {

    private val settingsState: MutableState<JSONObject?> = mutableStateOf(null)
    private val statusState = mutableStateOf("")
    private val micGranted = mutableStateOf(false)

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
            val tabs = listOf("Setup", "Speech", "Cleanup", "Words", "History")
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
                            4 -> HistoryScreen()
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
                val hotkeys = s.optJSONObject("hotkeys") ?: JSONObject()
                var tapMs by remember {
                    mutableStateOf(hotkeys.optLong("smart_tap_ms", 150L).toString())
                }
                OutlinedTextField(
                    value = tapMs,
                    onValueChange = { tapMs = it.filter(Char::isDigit).take(4) },
                    label = { Text("Smart tap (ms): tap shorter toggles, hold talks") },
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
        }
    }

    // -- Speech to Text -------------------------------------------------

    private fun sttObj(s: JSONObject): JSONObject =
        s.optJSONObject("stt") ?: JSONObject().also { s.put("stt", it) }

    private fun entriesOf(parent: JSONObject, key: String): JSONArray =
        parent.optJSONArray(key) ?: JSONArray().also { parent.put(key, it) }

    private fun entrySummary(e: JSONObject): String {
        return when (e.optString("kind")) {
            "Local" -> "on-device (not supported yet)"
            "SelfHosted" -> "${e.optString("url")} · ${e.optString("model")}"
            else -> "${e.optString("provider")} · ${e.optString("model")}"
        }
    }

    @Composable
    private fun SttScreen(s: JSONObject) {
        val stt = sttObj(s)
        var dialog by remember { mutableStateOf<JSONObject?>(null) }
        var version by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Chain (top = first tried)") {
                Text(
                    "Local/on-device entries are skipped on mobile until on-device inference lands.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val entries = entriesOf(stt, "entries")
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.optString("name", "entry"))
                                Text(entrySummary(e), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = e.optBoolean("enabled", true),
                                onCheckedChange = {
                                    e.put("enabled", it); save(); version++
                                },
                            )
                            IconButton(onClick = { dialog = JSONObject(e.toString()) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = {
                                val next = JSONArray()
                                for (j in 0 until entries.length()) {
                                    if (j != i) next.put(entries.get(j))
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
                OutlinedTextField(
                    value = lang, onValueChange = { lang = it },
                    label = { Text("Language (auto or code)") },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    label = { Text("Custom prompt (blank = default)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = { lm.put("cleanup_prompt", prompt); save() }) {
                    Text("Save prompt")
                }
            }
            Section("Models (first enabled wins)") {
                val entries = entriesOf(lm, "entries")
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.optString("name", "model"))
                                Text(
                                    "${e.optString("kind")} · ${e.optString("model")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = e.optBoolean("enabled", true),
                                onCheckedChange = {
                                    e.put("enabled", it); save(); version++
                                },
                            )
                            IconButton(onClick = {
                                val next = JSONArray()
                                for (j in 0 until entries.length()) {
                                    if (j != i) next.put(entries.get(j))
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
        dialog?.let { d ->
            var name by remember { mutableStateOf(d.optString("name")) }
            var kind by remember { mutableStateOf(d.optString("kind", "Ollama")) }
            var url by remember { mutableStateOf(d.optString("url")) }
            var model by remember { mutableStateOf(d.optString("model")) }
            var provider by remember { mutableStateOf(d.optString("provider", "OpenAI")) }
            var key by remember { mutableStateOf(d.optString("api_key")) }
            var enabled by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Language model") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(value = name, onValueChange = { name = it },
                            label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        DropDown("Kind", kind, listOf("Ollama", "Local", "Cloud")) {
                            kind = it
                        }
                        if (kind == "Cloud") {
                            DropDown("Provider", provider,
                                listOf("Groq", "OpenRouter", "OpenAI", "Claude", "Google")) {
                                provider = it
                            }
                        }
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("URL") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it },
                            label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                        if (kind == "Cloud") {
                            OutlinedTextField(value = key, onValueChange = { key = it },
                                label = { Text("API key") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Enabled", modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        d.put("name", name).put("kind", kind)
                            .put("url", url.trim()).put("model", model.trim())
                            .put("provider", provider).put("api_key", key)
                            .put("enabled", enabled)
                        upsertEntry(lm, "entries", d)
                        save(); version++; dialog = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
    }

    // -- Dictionary -----------------------------------------------------

    @Composable
    private fun DictionaryScreen(s: JSONObject) {
        val dict = s.optJSONObject("dictionary") ?: JSONObject().also {
            s.put("dictionary", it)
        }
        var version by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") version
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Words (exact spellings)") {
                val arr = entriesOf(dict, "words")
                var next by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = next, onValueChange = { next = it },
                        label = { Text("New word") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (next.isNotBlank()) {
                            arr.put(next.trim()); next = ""
                            dict.put("words", arr); save(); version++
                        }
                    }) { Text("Add") }
                }
                for (i in 0 until arr.length()) {
                    val w = arr.optString(i)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val n = JSONArray()
                            for (j in 0 until arr.length()) {
                                if (j != i) n.put(arr.get(j))
                            }
                            dict.put("words", n); save(); version++
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
            Section("Snippets (trigger → replacement)") {
                val arr = entriesOf(dict, "snippets")
                var trig by remember { mutableStateOf("") }
                var repl by remember { mutableStateOf("") }
                OutlinedTextField(value = trig, onValueChange = { trig = it },
                    label = { Text("Trigger") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = repl, onValueChange = { repl = it },
                    label = { Text("Replacement") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    if (trig.isNotBlank() && repl.isNotBlank()) {
                        arr.put(JSONObject().put("trigger", trig.trim()).put("replacement", repl))
                        trig = ""; repl = ""
                        dict.put("snippets", arr); save(); version++
                    }
                }) { Text("Add snippet") }
                for (i in 0 until arr.length()) {
                    val sn = arr.optJSONObject(i) ?: continue
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sn.optString("trigger"))
                            Text(
                                sn.optString("replacement"),
                                style = MaterialTheme.typography.bodySmall,
                            )
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

    // -- History --------------------------------------------------------

    private data class Hist(
        val uuid: String,
        val text: String,
        val date: String,
        val words: Int,
    )

    @Composable
    private fun HistoryScreen() {
        var items by remember { mutableStateOf(loadHist()) }
        Column(modifier = Modifier.fillMaxSize()) {
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
}
