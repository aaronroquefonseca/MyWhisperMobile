package com.mywhisper.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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
    private val settingsBusy = mutableStateOf(false)
    private val settingsError = mutableStateOf("")
    private val histBusy = mutableStateOf(false)
    private val histError = mutableStateOf("")
    private val settingsGen = AtomicLong(0)
    private val histGen = AtomicLong(0)
    private val activityGen = AtomicLong(0)
    private val syncGen = AtomicLong(0)
    private val micGranted = mutableStateOf(false)
    private val overlayGranted = mutableStateOf(false)
    private val a11yOn = mutableStateOf(false)
    private val micRationale = mutableStateOf(false)
    private val micBlocked = mutableStateOf(false)
    private var micDeniedBefore = false
    private val workers = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null

    /** On-device pack state for the STT/LLM Local rows. Pure for unit tests. */
    internal enum class LocalPackState { PRESENT, MISSING, MISMATCH }

    /**
     * Lite-gated row state for the Local STT/LLM download rows.
     * NEED_FULL_FLAVOR = runner absent (lite flavor): the full-APK
     * pointer shows instead of Download; the rest mirror the
     * verified-pack row machine ([LocalModelDownload.RowState]).
     * Pure for unit tests.
     */
    internal enum class GatedRowState {
        NEED_FULL_FLAVOR, MISSING, DOWNLOADING, PRESENT, MISMATCH, FAILED
    }

    companion object {
        /** Next step when the mic request is denied. Pure for unit tests. */
        internal enum class MicDenyOutcome { RATIONALE, SETTINGS, TRANSIENT }

        /** Which floating-button prerequisite to ask for next. Pure for unit tests. */
        internal enum class OverlayCta { OVERLAY, A11Y, READY }

        @JvmStatic
        internal fun onboardingDoneCount(ime: Boolean, mic: Boolean, server: Boolean): Int =
            listOf(ime, mic, server).count { it }

        /**
         * True when at least one enabled, non-Local STT entry has a model
         * (SelfHosted additionally needs a URL; Cloud allows blank URL =
         * vendor default). Pure for unit tests.
         */
        @JvmStatic
        internal fun isServerConfigured(s: JSONObject?): Boolean {
            val entries = s?.optJSONObject("stt")?.optJSONArray("entries") ?: return false
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                if (!e.optBoolean("enabled", true)) continue
                if (e.optString("kind") == "Local") continue
                if (e.optString("model").isBlank()) continue
                if (e.optString("kind") == "SelfHosted" && e.optString("url").isBlank()) continue
                return true
            }
            return false
        }

        /**
         * Denied + rationale visible -> explain and retry; denied +
         * no rationale + previously denied -> permanently blocked, send
         * to Settings; otherwise a transient toast (e.g. first deny).
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun micDenyOutcome(shouldShowRationale: Boolean, deniedBefore: Boolean): MicDenyOutcome =
            when {
                shouldShowRationale -> MicDenyOutcome.RATIONALE
                deniedBefore -> MicDenyOutcome.SETTINGS
                else -> MicDenyOutcome.TRANSIENT
            }

        /** Overlay first, then the accessibility commit path. Pure for unit tests. */
        @JvmStatic
        internal fun overlayCta(overlay: Boolean, a11y: Boolean): OverlayCta =
            when {
                !overlay -> OverlayCta.OVERLAY
                !a11y -> OverlayCta.A11Y
                else -> OverlayCta.READY
            }

        /**
         * Generation guard for settings-page jobs (desktop `Rc::ptr_eq`
         * / `CHECK_EPOCH` parity): a worker commits its result only
         * when its generation still equals the screen's current one.
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun jobApplies(currentGen: Long, jobGen: Long): Boolean =
            CoreBridge.SettingsJobs.jobApplies(currentGen, jobGen)

        /** Next per-screen job generation. Pure for unit tests. */
        @JvmStatic
        internal fun nextJobGen(currentGen: Long): Long =
            CoreBridge.SettingsJobs.nextJobGen(currentGen)

        /**
         * True when the committed store warning leaves a retryable
         * reset behind (desktop `settings.reset.incomplete` parity).
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun resetNeedsRetry(warning: String?): Boolean =
            CoreBridge.SettingsJobs.resetNeedsRetry(warning)

        /**
         * Placeholder SHA pins (all zeros / blank) never verify: the
         * model is not downloadable and the entry must stay off.
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun isPlaceholderSha(sha: String): Boolean {
            val t = sha.trim()
            return t.isEmpty() || t.all { it == '0' }
        }

        /**
         * STT pack state via the existing verify ([LocalStt.isAvailable]:
         * all three pack files present and non-empty). Pure for unit tests.
         */
        @JvmStatic
        internal fun sttLocalPackState(packPresent: Boolean): LocalPackState =
            if (packPresent) LocalPackState.PRESENT else LocalPackState.MISSING

        /**
         * LLM pack state for [taskFile] against [assets] (parsed from
         * [LLM_MANIFEST_JSON]): missing file or placeholder-only pins
         * read as MISSING (fail closed — never enable on an unverified
         * file), a file matching no real pin reads as MISMATCH.
         * Pure for unit tests (java.io only, no Context).
         */
        @JvmStatic
        internal fun llmLocalPackState(
            taskFile: File,
            assets: List<LocalLlmManager.ModelAsset>,
        ): LocalPackState {
            if (!taskFile.isFile) return LocalPackState.MISSING
            val pins = assets.map { it.sha256 }.filter { !isPlaceholderSha(it) }
            if (pins.isEmpty()) return LocalPackState.MISSING
            return if (pins.any { LocalLlmManager.verifySha256(taskFile, it) }) {
                LocalPackState.PRESENT
            } else {
                LocalPackState.MISMATCH
            }
        }

        /**
         * Fail-closed gate: a Local entry may only be switched on with
         * a verified pack. Pure for unit tests.
         */
        @JvmStatic
        internal fun localToggleAllowed(state: LocalPackState): Boolean =
            state == LocalPackState.PRESENT

        /**
         * Lite-flavor gate for the Local STT/LLM rows: lite builds ship
         * no on-device runners (sherpa / MediaPipe are
         * fullImplementation-only), so downloads offered there could
         * never run. False (= runner absent) forces the NEED_FULL_FLAVOR
         * row, which shows the full-APK pointer instead of Download.
         * True (= full flavor) defers to the verified-pack row machine.
         * Fail-closed: NEED_FULL_FLAVOR never enables the toggle.
         * Pure for unit tests (runner presence injected).
         */
        @JvmStatic
        internal fun gatedRowState(
            packPresent: Boolean,
            packMismatch: Boolean,
            downloading: Boolean,
            error: String?,
            runnerSupported: Boolean,
        ): GatedRowState {
            if (!runnerSupported) return GatedRowState.NEED_FULL_FLAVOR
            return when (
                LocalModelDownload.resolve(packPresent, packMismatch, downloading, error)
            ) {
                LocalModelDownload.RowState.MISSING -> GatedRowState.MISSING
                LocalModelDownload.RowState.DOWNLOADING -> GatedRowState.DOWNLOADING
                LocalModelDownload.RowState.PRESENT -> GatedRowState.PRESENT
                LocalModelDownload.RowState.MISMATCH -> GatedRowState.MISMATCH
                LocalModelDownload.RowState.FAILED -> GatedRowState.FAILED
            }
        }

        /** True when the Local toggle may go live under runner gating. */
        @JvmStatic
        internal fun gatedToggleAllowed(state: GatedRowState): Boolean =
            state == GatedRowState.PRESENT

        /** Sherpa STT runner present (false on lite builds / JVM tests). Never throws. */
        @JvmStatic
        internal fun sttRunnerSupported(): Boolean =
            try {
                LocalStt.SherpaRunner.isSupported()
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }

        /** MediaPipe LLM runner present (false on lite builds / JVM tests). Never throws. */
        @JvmStatic
        internal fun llmRunnerSupported(): Boolean =
            try {
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
                true
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }

        /**
          * Downloadable LLM assets, parsed by [LocalLlmManager]. Kept
          * byte-identical in parsed content to
          * `scripts/local-llm-models.json` (see `LocalEnablementTest`
          * sync guard); binaries are NEVER committed.
          */
        internal const val LLM_MANIFEST_JSON = """{
  "provider": "mediapipe",
  "notes": "Binaries NEVER committed. Download via the in-app Text cleanup > Local row (SHA-256 verified) or scripts/fetch-local-llm.sh. SHAs below were verified by full download + sha256sum (public repos, no auth).",
  "models": [
    {
      "id": "gemma3-1b-it-int4",
      "url": "https://huggingface.co/aquhm/gemma3-1b-it-int4-litert/resolve/main/gemma3-1b-it-int4.task",
      "sha256": "e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee",
      "size_bytes": 554661243,
      "min_ram_mb": 3072,
      "note": "Small-phone option: ~0.5GB weights, fits 4-6GB phones (Note9-class)."
    },
    {
      "id": "gemma2-2b-it-q8",
      "url": "https://huggingface.co/MiCkSoftware/Gemma2-2B-IT-LiteRT/resolve/main/gemma2_q8_multi-prefill-seq_ekv1280.task",
      "sha256": "407aaa8f86f5450370d5fc6d274aae2e62f07ae91f9c5e97d1d7b1e05078c752",
      "size_bytes": 2702868915,
      "min_ram_mb": 7168,
      "note": "Capable phones only: ~2.5GB weights, needs 8GB+ RAM (Note9-class: use the 1B)."
    }
  ]
}"""
    }

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
        if (granted) {
            micRationale.value = false
            micBlocked.value = false
            return@registerForActivityResult
        }
        val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.RECORD_AUDIO,
        )
        when (micDenyOutcome(showRationale, micDeniedBefore)) {
            MicDenyOutcome.RATIONALE -> micRationale.value = true
            MicDenyOutcome.SETTINGS -> micBlocked.value = true
            MicDenyOutcome.TRANSIENT -> toast(getString(R.string.setup_mic_denied))
        }
        if (!micDeniedBefore) {
            micDeniedBefore = true
            getSharedPreferences("mw_state", MODE_PRIVATE).edit()
                .putBoolean("mic_denied_before", true).apply()
        }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) toast(getString(R.string.setup_notif_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micDeniedBefore = getSharedPreferences("mw_state", MODE_PRIVATE)
            .getBoolean("mic_denied_before", false)
        refreshPermStates()
        reload()
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStates()
        reload()
    }

    override fun onDestroy() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        // Drop late job results: the generation bump makes stale
        // workers no-ops instead of touching torn-down state.
        settingsGen.incrementAndGet()
        histGen.incrementAndGet()
        activityGen.incrementAndGet()
        syncGen.incrementAndGet()
        workers.shutdownNow()
        super.onDestroy()
    }

    /** Re-read runtime grants that can change in system Settings. */
    private fun refreshPermStates() {
        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        overlayGranted.value = try {
            Settings.canDrawOverlays(this)
        } catch (_: Exception) {
            false
        }
        a11yOn.value = try {
            MyWhisperAccessibility.isOn(this)
        } catch (_: Exception) {
            false
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        } catch (_: Exception) {
            toast(getString(R.string.setup_mic_denied))
        }
    }

    private fun reload() {
        // Generation-aware async load (desktop settings-page jobs
        // parity): the worker commits only when its generation is
        // still current, so a newer reload/save drops this result
        // instead of clobbering newer committed state.
        val gen = CoreBridge.SettingsJobs.nextJobGen(settingsGen.get()).also { settingsGen.set(it) }
        settingsBusy.value = true
        workers.execute {
            try {
                val committed = CoreBridge.getSettings().getOrThrow()
                val app = application as? MyWhisperApp
                val status = app?.coreError ?: "core ready"
                runOnUiThread {
                    if (!jobApplies(settingsGen.get(), gen)) return@runOnUiThread
                    settingsState.value = committed
                    statusState.value = status
                    settingsError.value = ""
                    settingsBusy.value = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!jobApplies(settingsGen.get(), gen)) return@runOnUiThread
                    statusState.value = "core error: ${e.message}"
                    settingsError.value = e.message ?: "failed"
                    settingsBusy.value = false
                }
            }
        }
    }

    private fun save() {
        // Async save with committed-state reflection: persist the
        // snapshot, then re-read the committed settings and apply
        // them (desktop reset Snapshot parity). Stale generations
        // drop, so rapid successive saves keep last-writer-wins.
        val snapshot = settingsState.value?.toString() ?: return
        val gen = CoreBridge.SettingsJobs.nextJobGen(settingsGen.get()).also { settingsGen.set(it) }
        settingsBusy.value = true
        workers.execute {
            try {
                CoreBridge.putSettings(JSONObject(snapshot)).getOrThrow()
                val committed = CoreBridge.getSettings().getOrThrow()
                runOnUiThread {
                    if (!jobApplies(settingsGen.get(), gen)) return@runOnUiThread
                    settingsState.value = committed
                    settingsError.value = ""
                    settingsBusy.value = false
                    // Prefs toggle: rebuild live foreground notifications,
                    // no process restart needed.
                    try {
                        OverlayService.refreshNotif(this)
                    } catch (_: Exception) {
                    }
                    try {
                        SyncService.refreshNotif(this)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!jobApplies(settingsGen.get(), gen)) return@runOnUiThread
                    settingsError.value = e.message ?: "failed"
                    settingsBusy.value = false
                    toast(getString(R.string.save_failed, e.message))
                }
            }
        }
    }

    /** Snackbar event bus: Activity/composable code emits, Root() displays. */
    private val snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private fun notify(msg: String) {
        snackbarEvents.tryEmit(msg)
    }

    private fun toast(msg: String) = notify(msg)

    private fun bgToast(msg: String) = notify(msg)

    /**
     * Push local mutations out immediately (desktop sync_after_change
     * parity): dictionary/history edits only reach peers on a round,
     * and the 60s loop is too slow to feel instant. No-op when sync
     * is stopped.
     */
    private fun kickSync() {
        workers.execute {
            try {
                CoreBridge.syncAllNow()
            } catch (_: Exception) {
            }
        }
    }

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
            ago < 60 -> getString(R.string.seen_just_now)
            ago < 3600 -> getString(R.string.seen_min_ago, ago / 60)
            else -> getString(R.string.seen_h_ago, ago / 3600)
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
            var tab by rememberSaveable { mutableIntStateOf(0) }
            val snackbarHost = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                snackbarEvents.collect { snackbarHost.showSnackbar(it) }
            }
            val tabs = listOf(
                                getString(R.string.tab_setup),
                                getString(R.string.tab_stt),
                                getString(R.string.tab_cleanup),
                                getString(R.string.tab_dict),
                                getString(R.string.tab_activity),
                                getString(R.string.tab_history),
                                getString(R.string.tab_devices),
                            )
            Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { pad ->
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
                        // Explicit recovery UI: boot/load failures keep
                        // the committed error visible with a retry that
                        // re-runs the generation-aware reload job.
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(statusState.value)
                            if (settingsError.value.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    settingsError.value,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { reload() },
                                enabled = !settingsBusy.value,
                            ) {
                                Text(
                                    if (settingsBusy.value) "Loading…"
                                    else stringResource(R.string.action_refresh),
                                )
                            }
                        }
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
    private fun NotifToggle(
        title: String,
        sub: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
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
                OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.action_change)) }
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
                            contentDescription = stringResource(R.string.drag_reorder),
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
            val imeDone = imeEnabled()
            val micDone = micGranted.value
            val serverDone = isServerConfigured(s)
            val doneCount = onboardingDoneCount(imeDone, micDone, serverDone)
            Section("Setup progress: $doneCount of 3") {
                if (doneCount == 3) {
                    Text(
                        "You're ready to dictate.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    (if (imeDone) "✓ " else "○ ") + "Enable + switch input method",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    (if (micDone) "✓ " else "○ ") + "Microphone granted",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    (if (serverDone) "✓ " else "○ ") + "Speech server configured",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Section(stringResource(R.string.setup_status)) {
                Text(statusState.value, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.setup_server_only),
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
                }) { Text(stringResource(R.string.setup_open_ime)) }
            }
            Section("2 · Switch to it") {
                val seen = imeSeenActive()
                Text(
                    if (seen.isNotEmpty()) getString(R.string.setup_active_now, seen)
                    else getString(R.string.setup_active_how),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                }) { Text(stringResource(R.string.setup_pick)) }
            }
            Section("3 · Microphone") {
                Text(
                    if (micGranted.value) "Microphone granted."
                    else "Microphone is required for dictation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { micPerm.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(stringResource(R.string.setup_mic_grant))
                }
            }
            if (micRationale.value) {
                AlertDialog(
                    onDismissRequest = { micRationale.value = false },
                    title = { Text(stringResource(R.string.setup_step3)) },
                    text = { Text(stringResource(R.string.setup_mic_no)) },
                    confirmButton = {
                        TextButton(onClick = {
                            micRationale.value = false
                            micPerm.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text(stringResource(R.string.setup_mic_grant)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { micRationale.value = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
            if (micBlocked.value) {
                AlertDialog(
                    onDismissRequest = { micBlocked.value = false },
                    title = { Text(stringResource(R.string.setup_step3)) },
                    text = { Text(stringResource(R.string.setup_mic_denied)) },
                    confirmButton = {
                        TextButton(onClick = {
                            micBlocked.value = false
                            openAppSettings()
                        }) { Text(stringResource(R.string.action_change)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { micBlocked.value = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
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
                        toast(getString(R.string.setup_notif_old))
                    }
                }) {
                    Text(stringResource(R.string.setup_notif_allow))
                }
                Spacer(Modifier.height(8.dp))
                // Per-mode notification policy: each toggle rebuilds
                // the live foreground notifications (no restart).
                val nprefs = s.optJSONObject("preferences") ?: JSONObject()
                NotifToggle(
                    title = getString(R.string.notif_pref_idle),
                    sub = getString(R.string.notif_pref_idle_sub),
                    checked = nprefs.optBoolean(NotifPolicy.KEY_IDLE_ONGOING, true),
                ) {
                    nprefs.put(NotifPolicy.KEY_IDLE_ONGOING, it)
                    s.put("preferences", nprefs)
                    save()
                }
                NotifToggle(
                    title = getString(R.string.notif_pref_rec),
                    sub = getString(R.string.notif_pref_rec_sub),
                    checked = nprefs.optBoolean(NotifPolicy.KEY_REC_ONGOING, true),
                ) {
                    nprefs.put(NotifPolicy.KEY_REC_ONGOING, it)
                    s.put("preferences", nprefs)
                    save()
                }
                NotifToggle(
                    title = getString(R.string.notif_pref_work),
                    sub = getString(R.string.notif_pref_work_sub),
                    checked = nprefs.optBoolean(NotifPolicy.KEY_WORK_PROGRESS, true),
                ) {
                    nprefs.put(NotifPolicy.KEY_WORK_PROGRESS, it)
                    s.put("preferences", nprefs)
                    save()
                }
                NotifToggle(
                    title = getString(R.string.notif_pref_sync),
                    sub = getString(R.string.notif_pref_sync_sub),
                    checked = nprefs.optBoolean(NotifPolicy.KEY_SYNC_PROGRESS, true),
                ) {
                    nprefs.put(NotifPolicy.KEY_SYNC_PROGRESS, it)
                    s.put("preferences", nprefs)
                    save()
                }
                NotifToggle(
                    title = getString(R.string.notif_pref_sync_ongoing),
                    sub = getString(R.string.notif_pref_sync_ongoing_sub),
                    checked = nprefs.optBoolean(NotifPolicy.KEY_SYNC_ONGOING, true),
                ) {
                    nprefs.put(NotifPolicy.KEY_SYNC_ONGOING, it)
                    s.put("preferences", nprefs)
                    save()
                }
            }
            Section(stringResource(R.string.setup_mic_button)) {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                val mode = prefs.optString("mic_mode", "Smart")
                Text(
                    when (mode) {
                        "Push" -> getString(R.string.setup_mode_push)
                        "Tap" -> getString(R.string.setup_mode_tap)
                        else -> getString(R.string.setup_mode_smart)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                DropDown(getString(R.string.setup_behavior_label), mode, listOf("Smart", "Push", "Tap")) {
                    prefs.put("mic_mode", it)
                    s.put("preferences", prefs)
                    // save() already reflects the committed state.
                    save()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setup_pill))
                        Text(
                            stringResource(R.string.setup_pill_sub),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = prefs.optBoolean("show_pill", true),
                        onCheckedChange = {
                            prefs.put("show_pill", it)
                            s.put("preferences", prefs)
                            save()
                        },
                    )
                }
                val hotkeys = s.optJSONObject("hotkeys") ?: JSONObject()
                var tapMs by remember {
                    mutableStateOf(hotkeys.optLong("smart_tap_ms", 150L).toString())
                }
                OutlinedTextField(
                    value = tapMs,
                    onValueChange = { tapMs = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.setup_tap_ceiling)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    hotkeys.put("smart_tap_ms", tapMs.toLongOrNull() ?: 150L)
                    s.put("hotkeys", hotkeys)
                    save()
                }) { Text(stringResource(R.string.setup_tap_save)) }
            }
            Section(stringResource(R.string.setup_float)) {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                val on = prefs.optBoolean("float_enabled", false)
                Text(
                    stringResource(R.string.setup_float_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (on) stringResource(R.string.setup_float_on)
                    else stringResource(R.string.setup_float_off),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                val overlayOk = overlayGranted.value
                val a11yOk = a11yOn.value
                Text(
                    (if (overlayOk) "✓ " else "○ ") + stringResource(R.string.setup_float_overlay),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    (if (a11yOk) "✓ " else "○ ") + stringResource(R.string.setup_float_a11y),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                when (overlayCta(overlayOk, a11yOk)) {
                    OverlayCta.OVERLAY -> Button(onClick = {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }) { Text(stringResource(R.string.setup_float_overlay)) }
                    OverlayCta.A11Y -> Button(onClick = {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text(stringResource(R.string.setup_float_a11y)) }
                    OverlayCta.READY -> Text(
                        stringResource(R.string.setup_float_on),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.setup_float_a11y_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.setup_float), modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = {
                        android.util.Log.i("MwFloat", "toggle -> $it")
                        prefs.put("float_enabled", it)
                        s.put("preferences", prefs)
                        save()
                        if (it) {
                            OverlayService.start(this@MainActivity)
                        } else {
                            OverlayService.stop(this@MainActivity)
                        }
                    })
                }
            }
            Section(stringResource(R.string.setup_prefs)) {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                DropDown(
                    getString(R.string.setup_theme), prefs.optString("theme", "System"),
                    listOf("System", "Light", "Dark"),
                ) {
                    prefs.put("theme", it)
                    s.put("preferences", prefs)
                    save()
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
            "Local" -> getString(R.string.stt_local_summary)
            "SelfHosted" -> "${e.optString("url")} · ${e.optString("model")}"
            else -> "${e.optString("provider")} · ${e.optString("model")}"
        }
    }

    /**
     * Parsed LLM download assets (empty when the manifest is
     * unreadable — download and enable stay fail-closed).
     */
    private fun llmAssets(): List<LocalLlmManager.ModelAsset> =
        try {
            LocalLlmManager.parseManifest(LLM_MANIFEST_JSON)
        } catch (_: Exception) {
            emptyList()
        }

    /** Pack state for one catalog model (SHA-verified). */
    private fun sttModelPackState(model: LocalSttModels.Entry): LocalPackState =
        try {
            when (LocalSttModels.stateFor(filesDir, model)) {
                LocalModelDownload.RowState.PRESENT -> LocalPackState.PRESENT
                LocalModelDownload.RowState.MISMATCH -> LocalPackState.MISMATCH
                else -> LocalPackState.MISSING
            }
        } catch (_: Exception) {
            LocalPackState.MISSING
        }

    /** LLM pack state for this device (existing verify: [LocalLlmManager] SHA check). */
    private fun llmPackState(): LocalPackState =
        try {
            llmLocalPackState(LocalLlmManager.defaultTaskFile(filesDir), llmAssets())
        } catch (_: Exception) {
            LocalPackState.MISSING
        }

    /** Total device RAM in MB (selector warning only; 0 when unreadable). */
    private fun totalRamMb(): Long =
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / 1_000_000
        } catch (_: Exception) {
            0L
        }

    /** Fail-closed Local toggle: ON only with a verified pack, else a clear message. */
    private fun onLocalToggle(
        e: JSONObject,
        on: Boolean,
        state: LocalPackState,
        bump: () -> Unit,
        runnerSupported: Boolean = true,
    ) {
        if (!runnerSupported) {
            toast(getString(R.string.local_need_full))
            bump()
            return
        }
        if (on && !localToggleAllowed(state)) {
            toast(getString(R.string.local_enable_blocked))
            bump()
            return
        }
        e.put("enabled", on)
        save()
        bump()
    }

    @Composable
    private fun SttScreen(s: JSONObject) {
        val stt = sttObj(s)
        var dialog by remember { mutableStateOf<JSONObject?>(null) }
        var localInfo by remember { mutableStateOf(false) }
        var version by remember { mutableIntStateOf(0) }
        val statePrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        // Selected catalog model + per-model verify, once per
        // save-generation (not per row or keystroke); enabling Local
        // without a verified selected model fails closed.
        val selectedModel = remember(version) { LocalStt.selectedEntry(statePrefs) }
        val modelPacks = remember(version) {
            LocalSttModels.MODELS.associate { m -> m.id to sttModelPackState(m) }
        }
        fun packOf(id: String): LocalPackState =
            modelPacks[id] ?: LocalPackState.MISSING
        val selPack = packOf(selectedModel.id)
        // Lite flavor ships no sherpa runner: the chain toggle and all
        // Download buttons gate on this (fail-closed, no download offered).
        val sttRunner = remember { sttRunnerSupported() }
        // Per-model in-row download state (desktop fill_stt_models
        // parity: progress updates in place, Retry on failure).
        var dlBusy by remember { mutableStateOf(setOf<String>()) }
        var dlProg by remember { mutableStateOf(mapOf<String, Pair<Long, Long>>()) }
        var dlErr by remember { mutableStateOf(mapOf<String, String>()) }
        var deleteConfirm by remember { mutableStateOf<String?>(null) }
        fun rowOf(m: LocalSttModels.Entry): LocalModelDownload.RowState =
            LocalModelDownload.resolve(
                packOf(m.id) == LocalPackState.PRESENT,
                packOf(m.id) == LocalPackState.MISMATCH,
                dlBusy.contains(m.id),
                dlErr[m.id],
            )
        fun startModelDownload(m: LocalSttModels.Entry) {
            // Lite flavor: downloads could never run — refuse fail-closed.
            if (!sttRunner) {
                toast(getString(R.string.local_need_full))
                return
            }
            if (dlBusy.contains(m.id)) return
            dlBusy = dlBusy + m.id
            dlErr = dlErr - m.id
            dlProg = dlProg + (m.id to (0L to LocalSttModels.totalBytes(m)))
            workers.execute {
                try {
                    LocalSttModels.download(filesDir, m) { r, t ->
                        runOnUiThread { dlProg = dlProg + (m.id to (r to t)) }
                    }
                    runOnUiThread { dlBusy = dlBusy - m.id; version++ }
                } catch (ex: Exception) {
                    runOnUiThread {
                        dlBusy = dlBusy - m.id
                        dlErr = dlErr + (m.id to (ex.message ?: "failed"))
                        version++
                    }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") version
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section(stringResource(R.string.stt_chain)) {
                Text(
                    stringResource(R.string.stt_chain_sub),
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
                                    // Chain row follows the selected catalog
                                    // model (downloads live in On-device
                                    // models below); the toggle stays
                                    // fail-closed on its verified pack.
                                    // Lite flavor (no runner): full-APK
                                    // pointer instead of pack status.
                                    if (!sttRunner) {
                                        Text(
                                            stringResource(R.string.local_need_full),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        Text(
                                            when (selPack) {
                                                LocalPackState.PRESENT ->
                                                    stringResource(R.string.local_pack_present)
                                                LocalPackState.MISSING ->
                                                    stringResource(R.string.local_pack_missing)
                                                LocalPackState.MISMATCH ->
                                                    stringResource(R.string.local_pack_mismatch)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (selPack == LocalPackState.PRESENT) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            R.string.stt_using_model,
                                            selectedModel.name,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (isLocal) {
                                Switch(
                                    checked = e.optBoolean("enabled", true),
                                    onCheckedChange = {
                                        onLocalToggle(e, it, selPack, { version++ }, sttRunner)
                                    },
                                )
                                TextButton(onClick = { localInfo = true }) { Text(stringResource(R.string.stt_local_why)) }
                            } else {
                                Switch(
                                    checked = e.optBoolean("enabled", true),
                                    onCheckedChange = {
                                        e.put("enabled", it); save(); version++
                                    },
                                )
                                IconButton(onClick = { dialog = JSONObject(e.toString()) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                                }
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
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    dialog = newSttEntry("SelfHosted")
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.stt_add))
                }
            }
            Section(stringResource(R.string.stt_models_title)) {
                Text(
                    stringResource(R.string.stt_models_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.local_arm64_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!sttRunner) {
                    Text(
                        stringResource(R.string.local_need_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val captureLang = stt.optString("language", "auto").ifBlank { "auto" }
                val captureLabel = langs.firstOrNull { it.first == captureLang }?.second
                    ?: captureLang
                for (m in LocalSttModels.MODELS) {
                    val row = rowOf(m)
                    val isSelected = m.id == selectedModel.id
                    val prog = dlProg[m.id]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (isSelected) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ) else CardDefaults.cardColors(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        m.sizeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isSelected) {
                                    OutlinedButton(onClick = {}, enabled = false) {
                                        Text(stringResource(R.string.action_using))
                                    }
                                } else {
                                    OutlinedButton(onClick = {
                                        LocalStt.setSelectedEntry(statePrefs, m.id)
                                        version++
                                    }) {
                                        Text(stringResource(R.string.action_use))
                                    }
                                }
                            }
                            // Badges: languages + dictionary (prominent) —
                            // no streaming claims (owner-waived).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (m.langMode) {
                                        LocalSttModels.LangMode.AUTO ->
                                            stringResource(
                                                R.string.stt_langs_auto,
                                                m.languages.size,
                                            )
                                        LocalSttModels.LangMode.MANUAL ->
                                            stringResource(
                                                R.string.stt_lang_single,
                                                langs.firstOrNull {
                                                    it.first == m.languages.first()
                                                }?.second ?: m.languages.first(),
                                            )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (m.dictionary) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.stt_dict_badge),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                m.runtimeNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                when (m.langMode) {
                                    LocalSttModels.LangMode.AUTO ->
                                        stringResource(R.string.stt_lang_follows, captureLabel)
                                    LocalSttModels.LangMode.MANUAL ->
                                        stringResource(
                                            R.string.stt_lang_fixed,
                                            langs.firstOrNull {
                                                it.first == m.languages.first()
                                            }?.second ?: m.languages.first(),
                                        )
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                when (row) {
                                    LocalModelDownload.RowState.PRESENT ->
                                        stringResource(R.string.local_pack_present)
                                    LocalModelDownload.RowState.MISMATCH ->
                                        stringResource(R.string.local_pack_mismatch)
                                    LocalModelDownload.RowState.DOWNLOADING -> {
                                        val total = prog?.second ?: 0L
                                        val received = prog?.first ?: 0L
                                        if (total > 0) {
                                            stringResource(
                                                R.string.local_downloading_mb,
                                                m.name,
                                                received / 1_000_000,
                                                total / 1_000_000,
                                            )
                                        } else {
                                            stringResource(R.string.local_downloading, m.name)
                                        }
                                    }
                                    LocalModelDownload.RowState.FAILED ->
                                        stringResource(
                                            R.string.local_download_failed,
                                            dlErr[m.id] ?: "failed",
                                        )
                                    LocalModelDownload.RowState.MISSING ->
                                        stringResource(R.string.local_pack_missing)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row == LocalModelDownload.RowState.PRESENT) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                stringResource(
                                    R.string.stt_pack_dest,
                                    LocalSttModels.dirFor(filesDir, m).absolutePath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                Spacer(Modifier.height(4.dp))
                                val frac = LocalModelDownload.progressFraction(
                                    prog?.first ?: 0L,
                                    prog?.second?.takeIf { it > 0 },
                                )
                                if (frac != null) {
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!sttRunner) {
                                    // Lite flavor: no Download offered (could
                                    // never run) — full-APK pointer instead.
                                    Text(
                                        stringResource(R.string.local_need_full),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else when (row) {
                                    LocalModelDownload.RowState.DOWNLOADING -> {}
                                    LocalModelDownload.RowState.FAILED -> {
                                        OutlinedButton(onClick = { startModelDownload(m) }) {
                                            Text(stringResource(R.string.action_retry))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { deleteConfirm = m.id }) {
                                            Text(stringResource(R.string.action_delete))
                                        }
                                    }
                                    LocalModelDownload.RowState.PRESENT,
                                    LocalModelDownload.RowState.MISMATCH,
                                    -> {
                                        if (row == LocalModelDownload.RowState.MISMATCH) {
                                            OutlinedButton(onClick = { startModelDownload(m) }) {
                                                Text(stringResource(R.string.local_download))
                                            }
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        TextButton(onClick = { deleteConfirm = m.id }) {
                                            Text(stringResource(R.string.action_delete))
                                        }
                                    }
                                    LocalModelDownload.RowState.MISSING -> {
                                        OutlinedButton(onClick = { startModelDownload(m) }) {
                                            Text(stringResource(R.string.local_download))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Section(stringResource(R.string.stt_capture)) {
                var lang by remember { mutableStateOf(stt.optString("language", "auto")) }
                Text(stringResource(R.string.stt_language), style = MaterialTheme.typography.bodyMedium)
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
                    Text(stringResource(R.string.stt_vad), modifier = Modifier.weight(1f))
                    Switch(checked = vad, onCheckedChange = {
                        stt.put("vad_enabled", it); save(); version++
                    })
                }
                var gate by remember { mutableStateOf(stt.optDouble("vad_threshold", 0.1).toFloat()) }
                Text(stringResource(R.string.stt_vad_gate, gate))
                Slider(
                    value = gate, onValueChange = { gate = it },
                    onValueChangeFinished = {
                        stt.put("vad_threshold", gate.toDouble()); save()
                    },
                    valueRange = 0f..0.5f,
                )
                Spacer(Modifier.height(4.dp))
                val delayOptions = listOf(0L to getString(R.string.stt_delay_off), 100L to "100 ms", 250L to "250 ms", 500L to "500 ms", 1000L to "1 s")
                var delayMs by remember { mutableStateOf(stt.optLong("release_delay_ms", 0L)) }
                Text(stringResource(R.string.stt_delay), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.stt_delay_sub), style = MaterialTheme.typography.bodySmall)
                var delayOpen by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { delayOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(delayOptions.firstOrNull { it.first == delayMs }?.second ?: "$delayMs ms")
                }
                DropdownMenu(expanded = delayOpen, onDismissRequest = { delayOpen = false }) {
                    delayOptions.forEach { (ms, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                delayOpen = false
                                delayMs = ms
                                stt.put("release_delay_ms", ms); save(); version++
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    stt.put("language", lang.ifBlank { "auto" }); save()
                }) { Text(stringResource(R.string.stt_save_capture)) }
            }
        }
        if (localInfo) {
            AlertDialog(
                onDismissRequest = { localInfo = false },
                title = { Text(stringResource(R.string.stt_local_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.stt_local_body))
                        Spacer(Modifier.height(4.dp))
                        if (!sttRunner) {
                            Text(
                                stringResource(R.string.local_need_full),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            when (selPack) {
                                LocalPackState.PRESENT ->
                                    stringResource(R.string.local_pack_present)
                                LocalPackState.MISSING ->
                                    stringResource(R.string.local_pack_missing)
                                LocalPackState.MISMATCH ->
                                    stringResource(R.string.local_pack_mismatch)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { localInfo = false }) { Text(stringResource(R.string.action_gotit)) } },
            )
        }
        deleteConfirm?.let { doomedId ->
            val doomed = LocalSttModels.findById(doomedId)
            AlertDialog(
                onDismissRequest = { deleteConfirm = null },
                title = { Text(stringResource(R.string.local_delete_title)) },
                text = { Text(stringResource(R.string.local_delete_body, doomed.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteConfirm = null
                        workers.execute {
                            try { LocalSttModels.delete(filesDir, doomed) } catch (_: Exception) {}
                            runOnUiThread {
                                dlErr = dlErr - doomed.id
                                dlBusy = dlBusy - doomed.id
                                version++
                                toast(getString(R.string.local_deleted))
                            }
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirm = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        dialog?.let { EntryDialog(getString(R.string.stt_dialog_title), it, onClose = { dialog = null }) { edited ->
            upsertEntry(stt, "entries", edited)
            save(); version++; dialog = null
        } }
    }

    private fun newSttEntry(kind: String): JSONObject = JSONObject()
        .put("id", "stt-${UUID.randomUUID()}")
        .put("name", when (kind) { "Cloud" -> "Cloud"; "Local" -> "Local"; else -> "Server" })
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
        title: String,
        e: JSONObject,
        onClose: () -> Unit,
        onSave: (JSONObject) -> Unit,
    ) {
        var name by remember { mutableStateOf(e.optString("name")) }
        var kind by remember {
            mutableStateOf(
                e.optString("kind", "SelfHosted").takeIf {
                    it == "Cloud" || it == "Local"
                } ?: "SelfHosted",
            )
        }
        val isLocalKind = kind == "Local"
        var url by remember { mutableStateOf(e.optString("url")) }
        var model by remember { mutableStateOf(e.optString("model")) }
        var provider by remember { mutableStateOf(e.optString("provider", "OpenAI")) }
        var key by remember { mutableStateOf(e.optString("api_key")) }
        var streaming by remember { mutableStateOf(e.optBoolean("streaming", true)) }
        var enabled by remember { mutableStateOf(e.optBoolean("enabled", true)) }
        var showKey by remember { mutableStateOf(false) }
        var testStatus by remember { mutableStateOf<String?>(null) }
        val urlRequired = kind == "SelfHosted"
        val urlBlank = url.isBlank() && urlRequired
        val modelBlank = model.isBlank() && !isLocalKind
        val canSave = !urlBlank && !modelBlank
        fun testConnection(): String {
            val u = url.trim()
            val m = model.trim()
            if (urlRequired && u.isEmpty()) return "Enter a server URL first."
            if (m.isEmpty()) return "Enter a model first."
            if (u.isNotEmpty()) {
                val scheme = u.substringBefore("://", "")
                val ok = u.contains("://") && when (kind) {
                    "SelfHosted" -> scheme in setOf("http", "https", "ws", "wss")
                    else -> scheme in setOf("http", "https")
                }
                if (!ok) {
                    return if (kind == "SelfHosted") "URL must start with http(s):// or ws(s)://."
                    else "Base URL must start with http(s)://."
                }
            }
            return "Looks good — save, then dictate to verify against the server."
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("${stringResource(R.string.stt_dialog_title)} · $kind") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.stt_name)) }, modifier = Modifier.fillMaxWidth())
                    DropDown(getString(R.string.llm_kind), kind, listOf("SelfHosted", "Cloud", "Local")) {
                        kind = it; testStatus = null
                    }
                    if (isLocalKind) {
                        Text(
                            stringResource(R.string.stt_local_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (kind == "Cloud") {
                        DropDown(getString(R.string.stt_provider), provider,
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
                    if (!isLocalKind) {
                        OutlinedTextField(value = url, onValueChange = { url = it; testStatus = null },
                            label = {
                                Text(
                                    if (kind == "Cloud") stringResource(R.string.stt_url_cloud)
                                    else stringResource(R.string.stt_url_server),
                                )
                            },
                            isError = urlBlank,
                            supportingText = {
                                if (urlBlank) Text("Server URL is required.")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == "SelfHosted") {
                        Text(
                            stringResource(R.string.stt_selfhosted_compat),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!isLocalKind) {
                        OutlinedTextField(value = model, onValueChange = { model = it; testStatus = null },
                            label = { Text(stringResource(R.string.stt_model)) },
                            isError = modelBlank,
                            supportingText = {
                                if (modelBlank) Text("Model is required.")
                            },
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (!isLocalKind) {
                        OutlinedTextField(
                            value = key, onValueChange = { key = it },
                            label = { Text(stringResource(R.string.stt_apikey)) },
                            visualTransformation = if (showKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showKey = !showKey }) {
                                    Text(if (showKey) "hide" else "show")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (kind == "SelfHosted") {
                        Text(
                            "Bearer token for reverse-proxy setups. Empty means no token.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (kind == "SelfHosted") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.stt_streaming), modifier = Modifier.weight(1f))
                            Switch(checked = streaming, onCheckedChange = { streaming = it })
                        }
                        Text(
                            "http(s) URLs always use request/response; ws(s) URLs use the sherpa protocol.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stt_enabled), modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    if (!isLocalKind) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { testStatus = testConnection() }) {
                                Text("Test connection")
                            }
                        }
                    }
                    testStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Looks good")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        // New Local entries always start off (default-off):
                        // the list toggle enables them only with a verified pack.
                        e.put("name", name).put("kind", kind).put("url", url.trim())
                            .put("model", model.trim()).put("provider", provider)
                            .put("api_key", key).put("streaming", streaming)
                            .put("enabled", enabled && !isLocalKind)
                        onSave(e)
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
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
        var llmDlAssetId by remember { mutableStateOf<String?>(null) }
        // In-row download state (desktop fill_stt_models parity:
        // progress updates in place, Retry on failure).
        var llmDlBusy by remember { mutableStateOf(false) }
        var llmDlReceived by remember { mutableStateOf(0L) }
        var llmDlTotal by remember { mutableStateOf<Long?>(null) }
        var llmDlError by remember { mutableStateOf<String?>(null) }
        var llmDeleteConfirm by remember { mutableStateOf(false) }
        var version by remember { mutableIntStateOf(0) }
        // Pack check (SHA over a hundreds-of-MB file) runs once per
        // save-generation, not per row or keystroke; enabling Local
        // without a verified file fails closed.
        val llmState = remember(version) { llmPackState() }
        val assets = remember { llmAssets() }
        val llmPicked = assets.firstOrNull { it.id == llmDlAssetId } ?: assets.firstOrNull()
        val llmRow = LocalModelDownload.resolve(
            llmState == LocalPackState.PRESENT,
            llmState == LocalPackState.MISMATCH,
            llmDlBusy,
            llmDlError,
        )
        // Lite flavor ships no MediaPipe runner: no Download offered.
        val llmRunner = remember { llmRunnerSupported() }
        fun startLlmDownload() {
            // Lite flavor: downloads could never run — refuse fail-closed.
            if (!llmRunner) {
                toast(getString(R.string.local_need_full))
                return
            }
            val picked = llmPicked ?: return
            if (llmDlBusy || isPlaceholderSha(picked.sha256)) return
            llmDlBusy = true
            llmDlError = null
            llmDlReceived = 0L
            llmDlTotal = picked.sizeBytes.takeIf { it > 0 }
            workers.execute {
                try {
                    val dest = LocalLlmManager.defaultTaskFile(filesDir)
                    LocalLlmManager.downloadModel(picked, dest) { r, t ->
                        runOnUiThread { llmDlReceived = r; llmDlTotal = t }
                    }
                    runOnUiThread { llmDlBusy = false; version++ }
                } catch (ex: Exception) {
                    runOnUiThread {
                        llmDlBusy = false
                        llmDlError = ex.message ?: "failed"
                        version++
                    }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") version
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section(stringResource(R.string.cleanup_title)) {
                val on = lm.optBoolean("cleanup_enabled", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cleanup_enable), modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = {
                        lm.put("cleanup_enabled", it); save(); version++
                    })
                }
                // Effective prompt state: blank stored = built-in default.
                var defaultText by remember { mutableStateOf<String?>(null) }
                var promptFailed by remember { mutableStateOf(false) }
                if (defaultText == null) {
                    LaunchedEffect(Unit) {
                        workers.execute {
                            try {
                                val prompt = CoreBridge.defaultCleanupPrompt().getOrThrow()
                                runOnUiThread {
                                    defaultText = prompt
                                    promptFailed = false
                                }
                            } catch (_: Exception) {
                                runOnUiThread {
                                    defaultText = ""
                                    promptFailed = true
                                }
                            }
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
                if (promptFailed) {
                    // Explicit recovery UI for the async prompt job.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Couldn't load the built-in prompt.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            defaultText = null
                            promptFailed = false
                        }) { Text(stringResource(R.string.action_refresh)) }
                    }
                }
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
                                Text(if (expanded) stringResource(R.string.action_show_less) else stringResource(R.string.cleanup_show_all, lines.size))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = { editor = true }) {
                        Text(if (isDefault) stringResource(R.string.cleanup_customize) else stringResource(R.string.cleanup_edit))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !isDefault,
                        onClick = { lm.put("cleanup_prompt", ""); save(); version++ },
                    ) {
                        Text(stringResource(R.string.cleanup_reset))
                    }
                }
                if (editor) {
                    var draft by remember { mutableStateOf(effective) }
                    AlertDialog(
                        onDismissRequest = { editor = false },
                        title = { Text(stringResource(R.string.cleanup_dialog_title)) },
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
                            }) { Text(stringResource(R.string.action_save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { editor = false }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
            }
            Section(stringResource(R.string.cleanup_models)) {
                Text(
                    stringResource(R.string.cleanup_models_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.local_arm64_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!llmRunner) {
                    Text(
                        stringResource(R.string.local_need_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                                    if (isLocal) getString(R.string.cleanup_local_summary)
                                    else "${e.optString("kind")} · ${e.optString("model")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (isLocal) {
                                    // Lite flavor (no runner): full-APK
                                    // pointer instead of pack status.
                                    if (!llmRunner) {
                                        Text(
                                            stringResource(R.string.local_need_full),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        Text(
                                            when (llmRow) {
                                                LocalModelDownload.RowState.PRESENT ->
                                                    stringResource(R.string.local_pack_present)
                                                LocalModelDownload.RowState.MISMATCH ->
                                                    stringResource(R.string.local_pack_mismatch)
                                                LocalModelDownload.RowState.DOWNLOADING -> {
                                                    val total = llmDlTotal
                                                    if (total != null && total > 0) {
                                                        stringResource(
                                                            R.string.local_downloading_mb,
                                                            llmPicked?.id ?: "",
                                                            llmDlReceived / 1_000_000,
                                                            total / 1_000_000,
                                                        )
                                                    } else {
                                                        stringResource(
                                                            R.string.local_downloading,
                                                            llmPicked?.id ?: "",
                                                        )
                                                    }
                                                }
                                                LocalModelDownload.RowState.FAILED ->
                                                    stringResource(
                                                        R.string.local_download_failed,
                                                        llmDlError ?: "failed",
                                                    )
                                                LocalModelDownload.RowState.MISSING ->
                                                    stringResource(R.string.local_pack_missing)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (llmRow == LocalModelDownload.RowState.PRESENT) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                        )
                                    }
                                    // Secondary details line: model path only
                                    // (download is the primary path now).
                                    Text(
                                        stringResource(
                                            R.string.llm_pack_dest,
                                            LocalLlmManager.defaultTaskFile(filesDir).absolutePath,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (llmRow == LocalModelDownload.RowState.DOWNLOADING) {
                                        Spacer(Modifier.height(4.dp))
                                        val frac = LocalModelDownload.progressFraction(
                                            llmDlReceived, llmDlTotal,
                                        )
                                        if (frac != null) {
                                            LinearProgressIndicator(
                                                progress = { frac },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    val noPin = llmPicked == null ||
                                        isPlaceholderSha(llmPicked.sha256)
                                    if (llmRow != LocalModelDownload.RowState.DOWNLOADING &&
                                        llmRow != LocalModelDownload.RowState.PRESENT
                                    ) {
                                        if (llmPicked != null && assets.size > 1) {
                                            DropDown(
                                                getString(R.string.llm_model_label),
                                                llmPicked.id,
                                                assets.map { it.id },
                                            ) { llmDlAssetId = it }
                                        }
                                    }
                                    if (noPin && llmRow != LocalModelDownload.RowState.PRESENT) {
                                        Text(
                                            stringResource(R.string.llm_no_pin),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    val minRam = llmPicked?.minRamMb ?: 0L
                                    if (minRam > 0) {
                                        Text(
                                            stringResource(R.string.llm_ram_need, minRam),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        val ramMb = remember { totalRamMb() }
                                        if (ramMb > 0 && ramMb < minRam &&
                                            llmRow != LocalModelDownload.RowState.PRESENT
                                        ) {
                                            Text(
                                                stringResource(R.string.llm_ram_warn),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!llmRunner) {
                                            // Lite flavor: no Download offered
                                            // (could never run) — full-APK
                                            // pointer instead.
                                            Text(
                                                stringResource(R.string.local_need_full),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        } else when {
                                            llmRow == LocalModelDownload.RowState.DOWNLOADING -> {}
                                            llmRow == LocalModelDownload.RowState.FAILED -> {
                                                OutlinedButton(
                                                    enabled = !noPin,
                                                    onClick = { startLlmDownload() },
                                                ) {
                                                    Text(stringResource(R.string.action_retry))
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                TextButton(onClick = { llmDeleteConfirm = true }) {
                                                    Text(stringResource(R.string.action_delete))
                                                }
                                            }
                                            llmRow == LocalModelDownload.RowState.PRESENT ||
                                                llmRow == LocalModelDownload.RowState.MISMATCH -> {
                                                if (llmRow == LocalModelDownload.RowState.MISMATCH) {
                                                    OutlinedButton(
                                                        enabled = !noPin,
                                                        onClick = { startLlmDownload() },
                                                    ) {
                                                        Text(stringResource(R.string.local_download))
                                                    }
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                TextButton(onClick = { llmDeleteConfirm = true }) {
                                                    Text(stringResource(R.string.action_delete))
                                                }
                                            }
                                            else -> {
                                                OutlinedButton(
                                                    enabled = !noPin,
                                                    onClick = { startLlmDownload() },
                                                ) {
                                                    Text(stringResource(R.string.local_download))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (isLocal) {
                                Switch(
                                    checked = e.optBoolean("enabled", true),
                                    onCheckedChange = {
                                        onLocalToggle(e, it, llmState, { version++ }, llmRunner)
                                    },
                                )
                                TextButton(onClick = { localInfo = true }) { Text(stringResource(R.string.stt_local_why)) }
                            } else {
                                Switch(
                                    checked = e.optBoolean("enabled", true),
                                    onCheckedChange = {
                                        e.put("enabled", it); save(); version++
                                    },
                                )
                                IconButton(onClick = { dialog = JSONObject(e.toString()) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                                }
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
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
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
                    Text(stringResource(R.string.cleanup_add))
                }
            }
        }
        if (llmDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { llmDeleteConfirm = false },
                title = { Text(stringResource(R.string.local_delete_title)) },
                text = { Text(stringResource(R.string.local_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        llmDeleteConfirm = false
                        workers.execute {
                            try {
                                LocalLlmManager.deleteModel(
                                    LocalLlmManager.defaultTaskFile(filesDir), assets,
                                )
                            } catch (_: Exception) {}
                            runOnUiThread {
                                llmDlError = null
                                llmDlBusy = false
                                version++
                                toast(getString(R.string.local_deleted))
                            }
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { llmDeleteConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        if (localInfo) {
            AlertDialog(
                onDismissRequest = { localInfo = false },
                title = { Text(stringResource(R.string.cleanup_local_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.cleanup_local_body))
                        Spacer(Modifier.height(4.dp))
                        if (!llmRunner) {
                            Text(
                                stringResource(R.string.local_need_full),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            when (llmState) {
                                LocalPackState.PRESENT ->
                                    stringResource(R.string.local_pack_present)
                                LocalPackState.MISSING ->
                                    stringResource(R.string.local_pack_missing)
                                LocalPackState.MISMATCH ->
                                    stringResource(R.string.local_pack_mismatch)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                R.string.llm_pack_dest,
                                LocalLlmManager.defaultTaskFile(filesDir).absolutePath,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { localInfo = false }) { Text(stringResource(R.string.action_gotit)) } },
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
        // Internal values stay English (persisted); display is localized.
        val thinkLabels = mapOf(
            "Default" to getString(R.string.llm_thinking_default),
            "On" to getString(R.string.llm_thinking_on),
            "Off" to getString(R.string.llm_thinking_off),
        )
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
            title = { Text(kind) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.stt_name)) }, modifier = Modifier.fillMaxWidth())
                    DropDown(getString(R.string.llm_kind), kind, listOf("Ollama", "Local", "Cloud")) {
                        kind = it
                    }
                    if (kind == "Local") {
                        Text(
                            stringResource(R.string.llm_local_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (kind == "Cloud") {
                        DropDown(getString(R.string.stt_provider), provider,
                            listOf("Groq", "OpenRouter", "OpenAI", "Claude", "Google")) {
                            provider = it
                        }
                    }
                    if (kind != "Local") {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text(if (kind == "Cloud") stringResource(R.string.llm_url_cloud) else stringResource(R.string.llm_url)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it },
                            label = { Text(stringResource(R.string.stt_model)) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == "Cloud") {
                        OutlinedTextField(value = key, onValueChange = { key = it },
                            label = { Text(stringResource(R.string.stt_apikey)) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == "Ollama") {
                        OutlinedTextField(value = key, onValueChange = { key = it },
                            label = { Text(stringResource(R.string.stt_apikey)) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                        Text(
                            "Bearer token for reverse-proxy setups. Empty means no token.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (kind == "Ollama") {
                        OutlinedTextField(value = keepAlive, onValueChange = { keepAlive = it },
                            label = { Text(stringResource(R.string.llm_keepalive)) },
                            modifier = Modifier.fillMaxWidth())
                        DropDown(getString(R.string.llm_thinking), thinkLabels[thinking] ?: thinking, thinkLabels.values.toList()) { picked ->
                            thinking = thinkLabels.entries.firstOrNull { e -> e.value == picked }?.key ?: thinking
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stt_enabled), modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // New Local entries always start off (default-off):
                    // the list toggle enables them only with a verified pack.
                    val localKind = kind == "Local"
                    e.put("name", name).put("kind", kind)
                        .put("url", url.trim()).put("model", model.trim())
                        .put("provider", provider).put("api_key", key)
                        .put("keep_alive", keepAlive.trim())
                        .put("enabled", enabled && !localKind)
                    when (thinking) {
                        "On" -> e.put("thinking", true)
                        "Off" -> e.put("thinking", false)
                        else -> e.remove("thinking")
                    }
                    onSave(e)
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
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
        var snipDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
        var snipOld by remember { mutableStateOf<String?>(null) }
        var pendingDeleteWord by remember { mutableStateOf<String?>(null) }
        var pendingDeleteSnippet by remember { mutableStateOf<String?>(null) }
        var words by remember { mutableStateOf(CoreBridge.getWords()) }
        var snippets by remember { mutableStateOf(CoreBridge.getSnippets()) }
        fun reloadDict() {
            words = CoreBridge.getWords()
            snippets = CoreBridge.getSnippets()
        }
        // Words / Snippets segments (desktop Dictionary tabs): one list
        // visible at a time so huge dictionaries stay usable.
        var dictTab by remember { mutableIntStateOf(0) }
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = dictTab) {
                Tab(
                    selected = dictTab == 0,
                    onClick = { dictTab = 0 },
                    text = { Text(stringResource(R.string.dict_tab_words)) },
                )
                Tab(
                    selected = dictTab == 1,
                    onClick = { dictTab = 1 },
                    text = { Text(stringResource(R.string.dict_tab_snippets)) },
                )
            }
            if (dictTab == 0) {
            // Store-backed (desktop app_dictionary parity): edits hit
            // the sync tables directly, so transcription and sync see
            // them without a restart.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.dict_words_title, words.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.dict_words_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                var next by remember { mutableStateOf("") }
                var dup by remember { mutableStateOf(false) }
                var query by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = next, onValueChange = { next = it; dup = false },
                        label = { Text(stringResource(R.string.dict_new_word)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val w = next.trim()
                        if (w.isNotBlank()) {
                            if (words.any { it.equals(w, ignoreCase = true) }) {
                                dup = true
                            } else {
                                try {
                                    CoreBridge.addWord(w).getOrThrow()
                                    next = ""
                                } catch (e: Exception) {
                                    toast(e.message ?: "failed")
                                }
                                reloadDict(); version++
                                kickSync()
                            }
                        }
                    }) { Text(stringResource(R.string.action_add)) }
                }
                if (dup) {
                    Text(
                        stringResource(R.string.dict_dup),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.dict_search_words)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                val shown = words
                    .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                if (words.isEmpty()) {
                    Text(
                        stringResource(R.string.dict_no_words),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (shown.isEmpty()) {
                    Text(stringResource(R.string.dict_no_match), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(shown, key = { it }) { w ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(w, modifier = Modifier.weight(1f))
                                IconButton(onClick = { pendingDeleteWord = w }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                                }
                            }
                        }
                    }
                }
            }
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.dict_snippets_title, snippets.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.dict_snippets_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.dict_search_snippets)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                val shown = snippets
                    .filter { (trig, repl) ->
                        query.isBlank() ||
                            trig.contains(query, ignoreCase = true) ||
                            repl.contains(query, ignoreCase = true)
                    }
                if (snippets.isEmpty()) {
                    Text(stringResource(R.string.dict_no_snippets), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                } else if (shown.isEmpty()) {
                    Text(stringResource(R.string.dict_no_match), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(shown, key = { (trig, repl) -> trig + repl }) { (trig, repl) ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(trig)
                                        Text(
                                            "→ $repl",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconButton(onClick = {
                                        snipOld = trig
                                        snipDialog = trig to repl
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                                    }
                                    IconButton(onClick = {
                                        pendingDeleteSnippet = trig
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    snipOld = null
                    snipDialog = "" to ""
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dict_add_snippet))
                }
            }
            }
        }
        snipDialog?.let { (initTrig, initRepl) ->
            val isNew = snipOld == null
            var trig by remember { mutableStateOf(initTrig) }
            var repl by remember { mutableStateOf(initRepl) }
            AlertDialog(
                onDismissRequest = { snipDialog = null },
                title = { Text(if (isNew) stringResource(R.string.dict_add_snippet) else stringResource(R.string.dict_edit_snippet)) },
                text = {
                    Column {
                        OutlinedTextField(value = trig, onValueChange = { trig = it },
                            label = { Text(stringResource(R.string.dict_trigger)) },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = repl, onValueChange = { repl = it },
                            label = { Text(stringResource(R.string.dict_replacement)) },
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (trig.isNotBlank() && repl.isNotBlank()) {
                            try {
                                // Trigger rename = tombstone old + insert new.
                                val old = snipOld
                                if (old != null && old != trig.trim()) {
                                    CoreBridge.deleteSnippet(old).getOrThrow()
                                }
                                CoreBridge.putSnippet(trig, repl).getOrThrow()
                                version++
                                kickSync()
                            } catch (e: Exception) {
                                toast(e.message ?: "failed")
                            }
                        }
                        snipDialog = null
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = { snipDialog = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        // Desktop-style delete guard: deletions propagate to sync peers.
        pendingDeleteWord?.let { w ->
            AlertDialog(
                onDismissRequest = { pendingDeleteWord = null },
                title = { Text(stringResource(R.string.confirm_delete_title)) },
                text = { Text(stringResource(R.string.confirm_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            CoreBridge.deleteWord(w).getOrThrow()
                            kickSync()
                        } catch (e: Exception) {
                            toast(e.message ?: "failed")
                        }
                        pendingDeleteWord = null
                        reloadDict(); version++
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteWord = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        pendingDeleteSnippet?.let { t ->
            AlertDialog(
                onDismissRequest = { pendingDeleteSnippet = null },
                title = { Text(stringResource(R.string.confirm_delete_title)) },
                text = { Text(stringResource(R.string.confirm_delete_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            CoreBridge.deleteSnippet(t).getOrThrow()
                            kickSync()
                        } catch (e: Exception) {
                            toast(e.message ?: "failed")
                        }
                        pendingDeleteSnippet = null
                        reloadDict(); version++
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteSnippet = null }) { Text(stringResource(R.string.action_cancel)) } },
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
        // Generation-aware load: a newer refresh drops this worker's
        // commit instead of clobbering newer committed state.
        LaunchedEffect(version) {
            val gen = CoreBridge.SettingsJobs.nextJobGen(activityGen.get()).also { activityGen.set(it) }
            workers.execute {
                try {
                    val s = CoreBridge.usageStats().getOrThrow()
                    val arr = CoreBridge.dailyActivity(200).getOrThrow()
                    val d = List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        Day(o.getLong("dayStart"), o.getInt("words"), o.getInt("utterances"))
                    }
                    runOnUiThread {
                        if (!jobApplies(activityGen.get(), gen)) return@runOnUiThread
                        stats = s
                        days = d
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(activityGen.get(), gen)) return@runOnUiThread
                        err = e.message ?: "failed"
                        notify(err)
                    }
                }
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
            val u = stats?.optLong("dictations", 0L) ?: 0L
            val w = stats?.optLong("wordsSpoken", 0L) ?: 0L
            val wpm = stats?.optDouble("wordsPerMinute", 0.0) ?: 0.0
            val streak = stats?.optInt("streakDays", 0) ?: 0
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        stringResource(R.string.act_words), compactWords(w), stringResource(R.string.act_saved),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        stringResource(R.string.act_wpm),
                        if (wpm > 0) "${wpm.toInt()}" else stringResource(R.string.act_wpm_none),
                        stringResource(R.string.act_wpm_cap),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(
                        stringResource(R.string.act_dictations), "$u", stringResource(R.string.act_saved),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        stringResource(R.string.act_streak),
                        if (streak == 1) stringResource(R.string.act_streak_one) else stringResource(R.string.act_streak_n, streak),
                        stringResource(R.string.act_streak_cap),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Section(stringResource(R.string.act_chart)) {
                if (err.isNotEmpty()) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (days.isEmpty()) {
                    Text(stringResource(R.string.act_empty), style = MaterialTheme.typography.bodySmall)
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
                            title = { Text(date.ifEmpty { "·" }) },
                            text = {
                                Text(stringResource(R.string.act_day_body,
                                    d.words,
                                    if (d.words == 1) getString(R.string.act_word_one) else getString(R.string.act_word_many),
                                    d.utterances,
                                    if (d.utterances == 1) getString(R.string.act_dict_one) else getString(R.string.act_dict_many),
                                ))
                            },
                            confirmButton = {
                                TextButton(onClick = { tapped = null }) { Text(stringResource(R.string.action_close)) }
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedButton(onClick = { err = ""; version++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_refresh))
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
            Text(stringResource(R.string.act_less), style = MaterialTheme.typography.bodySmall)
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
            Text(stringResource(R.string.act_more), style = MaterialTheme.typography.bodySmall)
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
        var items by remember { mutableStateOf(emptyList<Hist>()) }
        var version by remember { mutableIntStateOf(0) }
        var confirmClear by remember { mutableStateOf(false) }
        @Suppress("UNUSED_EXPRESSION") version
        // Generation-aware history jobs (desktop reset parity):
        // each destructive op runs off-thread, then reflects the
        // committed store state; stale generations drop instead of
        // clobbering, failures keep an explicit retryable error.
        fun refreshHist() {
            val gen = CoreBridge.SettingsJobs.nextJobGen(histGen.get()).also { histGen.set(it) }
            histBusy.value = true
            workers.execute {
                try {
                    val committed = loadHistThrowing()
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        items = committed
                        histError.value = ""
                        histBusy.value = false
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        histError.value = e.message ?: "failed"
                        histBusy.value = false
                    }
                }
            }
        }
        fun purgeHist(days: Int) {
            val gen = CoreBridge.SettingsJobs.nextJobGen(histGen.get()).also { histGen.set(it) }
            histBusy.value = true
            workers.execute {
                try {
                    val n = CoreBridge.purgeHistory(days).getOrThrow()
                    val committed = loadHistThrowing()
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        items = committed
                        histError.value = ""
                        histBusy.value = false
                        bgToast(getString(R.string.hist_purged, n))
                    }
                    kickSync()
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        histError.value = e.message ?: "failed"
                        histBusy.value = false
                        bgToast(getString(R.string.hist_purge_failed, e.message))
                    }
                }
            }
        }
        fun clearHist() {
            val gen = CoreBridge.SettingsJobs.nextJobGen(histGen.get()).also { histGen.set(it) }
            histBusy.value = true
            workers.execute {
                try {
                    CoreBridge.dataOf(CoreBridge.nativeClearHistory())
                    val committed = loadHistThrowing()
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        items = committed
                        histError.value = ""
                        histBusy.value = false
                        toast(getString(R.string.hist_cleared))
                    }
                    kickSync()
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        histError.value = e.message ?: "failed"
                        histBusy.value = false
                        toast(getString(R.string.hist_clear_failed, e.message))
                    }
                }
            }
        }
        fun deleteHist(uuid: String) {
            val gen = CoreBridge.SettingsJobs.nextJobGen(histGen.get()).also { histGen.set(it) }
            histBusy.value = true
            workers.execute {
                try {
                    CoreBridge.dataOf(CoreBridge.nativeDeleteHistory(uuid))
                    val committed = loadHistThrowing()
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        items = committed
                        histError.value = ""
                        histBusy.value = false
                    }
                    kickSync()
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        histError.value = e.message ?: "failed"
                        histBusy.value = false
                        toast(getString(R.string.hist_delete_failed, e.message))
                    }
                }
            }
        }
        LaunchedEffect(Unit) { refreshHist() }
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
                            Text(if (privOpen) stringResource(R.string.action_hide) else stringResource(R.string.action_show))
                        }
                    }
                    if (privOpen) {
                        Spacer(Modifier.height(8.dp))
                        val priv = s.optJSONObject("privacy") ?: JSONObject().also {
                            s.put("privacy", it)
                        }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.hist_keep))
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
                            Text(stringResource(R.string.hist_cancelled))
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
                        label = { Text(stringResource(R.string.hist_days)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Button(onClick = {
                            priv.put("transcript_auto_delete_days", daysStr.toIntOrNull() ?: 0)
                            save()
                        }) { Text(stringResource(R.string.action_save)) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val days = daysStr.toIntOrNull() ?: 0
                            if (days > 0) {
                                purgeHist(days)
                            } else {
                                toast(getString(R.string.hist_set_days))
                            }
                        }) { Text(stringResource(R.string.hist_delete_older)) }
                    }
                }
            }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.hist_count, items.size), style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(
                        onClick = { refreshHist() },
                        enabled = !histBusy.value,
                    ) { Text(stringResource(R.string.action_refresh)) }
                    TextButton(
                        enabled = items.isNotEmpty() && !histBusy.value,
                        onClick = { confirmClear = true },
                    ) { Text(stringResource(R.string.action_clear)) }
                }
            }
            if (histError.value.isNotEmpty()) {
                // Explicit recovery UI: the committed error stays
                // visible with a retry that re-runs the load job.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        histError.value,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { histError.value = ""; refreshHist() },
                        enabled = !histBusy.value,
                    ) { Text(stringResource(R.string.action_refresh)) }
                }
            }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Clear all history?") },
                    text = { Text("This deletes every saved dictation on this device. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClear = false
                            clearHist()
                        }) { Text(stringResource(R.string.action_clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
            var histQuery by remember { mutableStateOf("") }
            OutlinedTextField(
                value = histQuery, onValueChange = { histQuery = it },
                label = { Text(stringResource(R.string.hist_search)) },
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
                                    getString(R.string.hist_words, h.words).let { "${h.date} · $it" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(
                                onClick = { deleteHist(h.uuid) },
                                enabled = !histBusy.value,
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadHist(): List<Hist> {
        return try {
            loadHistThrowing()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Throwing history load so jobs can keep a retryable error state. */
    private fun loadHistThrowing(): List<Hist> {
        val raw = CoreBridge.nativeGetHistory(200)
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Hist(
                o.optString("uuid"),
                o.optString("text"),
                o.optString("dateLabel"),
                o.optInt("wordCount"),
            )
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
            // Generation-aware commit: stale polls drop instead of
            // clobbering newer committed status/peers.
            val gen = CoreBridge.SettingsJobs.nextJobGen(syncGen.get()).also { syncGen.set(it) }
            workers.execute {
                val st = try {
                    CoreBridge.syncStatus().getOrThrow()
                } catch (_: Exception) {
                    null
                }
                val ps = loadPeers()
                val nm = CoreBridge.displayName()
                runOnUiThread {
                    if (!jobApplies(syncGen.get(), gen)) return@runOnUiThread
                    if (st != null) status = st
                    peers = ps
                    displayName = nm
                    version++
                }
            }
        }
        fun note(id: String, text: String) {
            notes = notes + (id to text)
        }
        @Suppress("UNUSED_EXPRESSION") version
        LaunchedEffect(Unit) {
            refresh()
            while (true) {
                delay(5000)
                val gen = CoreBridge.SettingsJobs.nextJobGen(syncGen.get()).also { syncGen.set(it) }
                workers.execute {
                    val st = try {
                        CoreBridge.syncStatus().getOrThrow()
                    } catch (_: Exception) {
                        null
                    }
                    val ps = loadPeers()
                    runOnUiThread {
                        if (!jobApplies(syncGen.get(), gen)) return@runOnUiThread
                        if (st != null) status = st
                        peers = ps
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section(stringResource(R.string.sync_this)) {
                val running = status?.optBoolean("running", false) == true
                Text(
                    if (running) {
                        val shown = displayName.ifEmpty { status?.optString("nodeId", "")?.take(12) + "…" }
getString(R.string.sync_running, shown)
                    } else {
                        "Sync stopped."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                val lan = status?.optString("lanIp", "") ?: ""
                if (lan.isNotEmpty()) {
                    Text(stringResource(R.string.sync_lan, lan), style = MaterialTheme.typography.bodySmall)
                }
                var name by remember { mutableStateOf(sync.optString("device_name", "")) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sync_name_hint, displayName.ifEmpty { getString(R.string.sync_name_default) })) },
                    modifier = Modifier.fillMaxWidth(),
                )
                var portStr by remember {
                    mutableStateOf(sync.optInt("listen_port", 42973).toString())
                }
                OutlinedTextField(
                    value = portStr, onValueChange = { portStr = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.sync_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val adv = sync.optBoolean("advertise", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.sync_advertise))
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
                    Text(stringResource(R.string.sync_enabled), modifier = Modifier.weight(1f))
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
                        Text(stringResource(R.string.sync_boot))
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
                    }) { Text(stringResource(R.string.action_save)) }
                    Spacer(Modifier.width(8.dp))
                    if (!running) {
                        Button(onClick = {
                            acquireMulticast()
                            // Single-owner start: the service owns
                            // syncStart (sync_start() begins with
                            // sync_stop(), so a concurrent activity-side
                            // syncStart() kills the service's manager and
                            // the first tap nets dead sync). Never call
                            // CoreBridge.syncStart() here; just refresh
                            // status once the service promotes.
                            SyncService.start(this@MainActivity)
                            workers.execute {
                                try {
                                    Thread.sleep(1_000)
                                } catch (_: InterruptedException) {
                                }
                                runOnUiThread { refresh() }
                            }
                        }) { Text(stringResource(R.string.action_start)) }
                    } else {
                        OutlinedButton(onClick = {
                            SyncService.stop(this@MainActivity)
                            CoreBridge.syncStop()
                            releaseMulticast()
                            refresh()
                        }) { Text(stringResource(R.string.action_stop)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = running && busy.isEmpty(),
                        onClick = {
                            busy = "syncing…"
                            workers.execute {
                                try {
                                    val lines = CoreBridge.syncAllNow().getOrThrow()
                                    bgToast(lines.firstOrNull() ?: getString(R.string.done))
                                } catch (e: Exception) {
                                    bgToast(getString(R.string.sync_failed, e.message))
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
            Section(stringResource(R.string.sync_trusted, trusted.size)) {
                if (trusted.isEmpty()) Text(stringResource(R.string.sync_none), style = MaterialTheme.typography.bodySmall)
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
                                    bgToast(getString(R.string.sync_remove_failed, e.message))
                                }
                                runOnUiThread { refresh() }
                            }
                        },
                    )
                }
            }
            Section(stringResource(R.string.sync_requests, requests.size)) {
                if (requests.isEmpty()) Text(stringResource(R.string.sync_requests_none), style = MaterialTheme.typography.bodySmall)
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
            Section(stringResource(R.string.sync_nearby, nearby.size)) {
                Text(
                    "Discovered on the LAN. Accept to trust and sync " +
                        "(works with desktops and other phones).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (nearby.isEmpty()) Text(stringResource(R.string.sync_nobody), style = MaterialTheme.typography.bodySmall)
                nearby.forEach { p ->
                    val id = p.optString("nodeId")
                    val nm = p.optString("name").ifEmpty { id.take(12) + "…" }
                    val addr = p.optJSONArray("discoveredAddrs")?.let { a ->
                        if (a.length() > 0) a.optString(0) else ""
                    } ?: ""
                    PeerRow(
                        p,
                        note = notes[id],
                        onRequest = {
                            workers.execute {
                                try {
                                    val msg = CoreBridge.syncAdd(id, addr, nm).getOrThrow()
                                    runOnUiThread { note(id, msg) }
                                    // A mutual round means both sides already
                                    // consented: promote to trusted.
                                    if (msg.startsWith("synced")) {
                                        try {
                                            CoreBridge.syncTrust(id, true).getOrThrow()
                                        } catch (_: Exception) {
                                        }
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { note(id, getString(R.string.sync_add_failed, e.message)) }
                                }
                                runOnUiThread { refresh() }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { refresh() }) { Text(stringResource(R.string.action_refresh)) }
            }
            Section("Add device manually") {
                Text(
                    "Copy the Device ID from MyWhisper on your other device. " +
                        "Send a pairing request, then accept it there. " +
                        "Both devices must be reachable.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { addOpen = true }) { Text(stringResource(R.string.sync_add_button)) }
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
                title = { Text(stringResource(R.string.sync_add_dialog)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = id, onValueChange = { id = it },
                            label = { Text(stringResource(R.string.sync_add_id)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = addr, onValueChange = { addr = it },
                            label = { Text(stringResource(R.string.sync_add_addr)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = nm, onValueChange = { nm = it },
                            label = { Text(stringResource(R.string.sync_add_name)) },
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
                    }) { Text(stringResource(R.string.sync_add_send)) }
                },
                dismissButton = { TextButton(onClick = { addOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }

    /** Trust then immediately run one round, reporting the outcome inline. */
    private fun acceptPeer(id: String, name: String, setNote: (String) -> Unit, refresh: () -> Unit) {
        workers.execute {
            try {
                CoreBridge.syncTrust(id, true).getOrThrow()
            } catch (e: Exception) {
                runOnUiThread { setNote(getString(R.string.sync_accept_failed, e.message)); refresh() }
                return@execute
            }
            try {
                val msg = CoreBridge.syncNow(id).getOrThrow()
                runOnUiThread { setNote(msg) }
            } catch (e: Exception) {
                // The usual case: we dialed, they recorded us as
                // pending, and rejected until they accept there.
                runOnUiThread {
                    setNote(getString(R.string.sync_waiting, name))
                }
            }
            runOnUiThread { refresh() }
        }
    }

    /** Apply Name/Port/Advertise/Enabled edits without a manual restart. */
    private fun restartSyncIfRunning(running: Boolean) {
        if (!running) {
            toast(getString(R.string.sync_saved))
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
        toast(getString(R.string.sync_restarted))
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
        onRequest: (() -> Unit)? = null,
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
                    onSync?.let { TextButton(onClick = it) { Text(stringResource(R.string.action_sync)) } }
                    onAccept?.let { TextButton(onClick = it) { Text(stringResource(R.string.action_accept)) } }
                    onRequest?.let { TextButton(onClick = it) { Text(stringResource(R.string.sync_request)) } }
                    onRemove?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_remove))
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
