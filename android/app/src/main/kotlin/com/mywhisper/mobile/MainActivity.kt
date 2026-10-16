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
import androidx.compose.foundation.clickable
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
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    /** Active background-download WorkInfo observers, keyed by unique work name. */
    private val dlWorkObservers = mutableMapOf<String, Observer<List<WorkInfo>>>()

    /**
     * Download-notification deep-link target: tapping a download
     * notification opens [Root] directly on the right page (STT vs
     * LLM models) via the [EXTRA_DL_TARGET] intent extra — cold start
     * (onCreate) and warm tap (onNewIntent, singleTop) alike.
     */
    private val deepLinkTab = mutableStateOf<Int?>(null)

    /** On-device pack state for the STT/LLM Local rows. Pure for unit tests. */
    internal enum class LocalPackState { PRESENT, MISSING, MISMATCH, INCOMPLETE }

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
         * Per-asset task file: `models/llm-<assetId>.task`, so several
         * downloaded LLM packs coexist (one file per asset) while the
         * legacy single `cleanup.task` slot stays untouched. Pure for
         * unit tests (file mapping only, no Context).
         */
        @JvmStatic
        internal fun llmTaskFileFor(dir: File, assetId: String): File {
            val safe = assetId.map { c ->
                if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
            }.joinToString("").ifBlank { "model" }
            return File(File(dir, "models"), "llm-$safe.task")
        }

        /**
         * Selected LLM asset: stored id wins, else the first manifest
         * asset, else null (empty manifest — download and enable stay
         * fail-closed). Pure for unit tests (stored id injected).
         */
        @JvmStatic
        internal fun llmSelectedAsset(
            assets: List<LocalLlmManager.ModelAsset>,
            storedId: String?,
        ): LocalLlmManager.ModelAsset? =
            assets.firstOrNull { it.id == storedId } ?: assets.firstOrNull()

        /**
         * Default LLM asset id (first manifest entry, "" when empty).
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun llmDefaultId(assets: List<LocalLlmManager.ModelAsset>): String =
            assets.firstOrNull()?.id ?: ""

        /**
         * Install state of one LLM asset: PRESENT when its per-asset
         * file (or the legacy `cleanup.task` slot) verifies against
         * its SHA; MISMATCH when COMPLETE files exist but nothing
         * verifies; INCOMPLETE when only a short file or a stale
         * `.part` lingers (interrupted download — resumable, never
         * "SHA mismatch"); MISSING otherwise (placeholder pins
         * included — never enable). Legacy-slot adoption keeps
         * existing downloads live without a re-download. Single-pass
         * via [llmInspect]. Pure for unit tests (java.io only).
         */
        @JvmStatic
        internal fun llmStateFor(
            dir: File,
            asset: LocalLlmManager.ModelAsset,
        ): LocalPackState = llmInspect(dir, asset).state

        /** Single-pass LLM read: install state + verified on-disk bytes. */
        internal data class LlmInspect(
            val state: LocalPackState,
            val verifiedBytes: Long,
        )

        /**
         * LLM install state + verified bytes in ONE pass: the slot and
         * the legacy slot are each SHA-verified at most once (length
         * checks in [hasLlmTrace]/[hasCompleteLlmFile] hash nothing).
         * [verify] is injected so unit tests can count calls. Pure for
         * unit tests (java.io only).
         */
        @JvmStatic
        internal fun llmInspectWithVerifier(
            dir: File,
            asset: LocalLlmManager.ModelAsset,
            verify: (File, String) -> Boolean,
        ): LlmInspect {
            if (isPlaceholderSha(asset.sha256)) {
                return LlmInspect(LocalPackState.MISSING, 0L)
            }
            val slot = llmTaskFileFor(dir, asset.id)
            val slotOk = verify(slot, asset.sha256)
            // Legacy slot is only probed when the per-asset slot
            // misses (adoption path) — a verified slot short-circuits.
            val legacy: File? = try {
                LocalLlmManager.defaultTaskFile(dir).takeIf { it != slot }
            } catch (_: Exception) {
                null
            }
            val legacyOk = !slotOk && legacy != null && verify(legacy, asset.sha256)
            val state = when {
                slotOk || legacyOk -> LocalPackState.PRESENT
                !hasLlmTrace(dir, asset) -> LocalPackState.MISSING
                hasCompleteLlmFile(dir, asset) -> LocalPackState.MISMATCH
                else -> LocalPackState.INCOMPLETE
            }
            val bytes = when {
                slotOk -> slot.length()
                legacyOk -> legacy?.length() ?: 0L
                else -> 0L
            }
            return LlmInspect(state, bytes)
        }

        /** Single-pass LLM inspect with the real SHA verifier. Pure. */
        @JvmStatic
        internal fun llmInspect(
            dir: File,
            asset: LocalLlmManager.ModelAsset,
        ): LlmInspect =
            llmInspectWithVerifier(dir, asset, LocalLlmManager::verifySha256)

        /**
         * True when any trace of [asset] lingers on disk: the slot, the
         * legacy slot, or a stale per-asset `.part` from an interrupted
         * download. Pure for unit tests (java.io only).
         */
        @JvmStatic
        internal fun hasLlmTrace(dir: File, asset: LocalLlmManager.ModelAsset): Boolean {
            val slot = llmTaskFileFor(dir, asset.id)
            if (slot.isFile && slot.length() > 0) return true
            try {
                val legacy = LocalLlmManager.defaultTaskFile(dir)
                if (legacy != slot && legacy.isFile && legacy.length() > 0) return true
            } catch (_: Exception) {
            }
            try {
                val models = File(dir, "models")
                val part = File(models, "${slot.name}.part.${asset.id}")
                if (part.isFile) return true
            } catch (_: Exception) {
            }
            return false
        }

        /**
         * True when a full-size (but unverified — PRESENT already
         * ruled out) file backs [asset]: only then does the MISMATCH
         * copy apply. A short file or a lone `.part` is an interrupted
         * download (INCOMPLETE). Unknown expected size ([sizeBytes] <=
         * 0) reads as complete (legacy MISMATCH behavior). Pure.
         */
        @JvmStatic
        internal fun hasCompleteLlmFile(dir: File, asset: LocalLlmManager.ModelAsset): Boolean {
            if (asset.sizeBytes <= 0) return true
            val slot = llmTaskFileFor(dir, asset.id)
            if (slot.isFile && slot.length() >= asset.sizeBytes) return true
            return try {
                val legacy = LocalLlmManager.defaultTaskFile(dir)
                legacy != slot && legacy.isFile && legacy.length() >= asset.sizeBytes
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Verified bytes already on disk for [asset] (INCOMPLETE
         * progress numerator; 0 when nothing usable lingers).
         * Single-pass via [llmInspect]. Pure.
         */
        @JvmStatic
        internal fun llmPresentBytes(dir: File, asset: LocalLlmManager.ModelAsset): Long =
            llmInspect(dir, asset).verifiedBytes

        /**
         * Ids with a verified LLM pack on disk, in manifest order.
         * Pure for unit tests (java.io only).
         */
        @JvmStatic
        internal fun llmInstalledIds(
            dir: File,
            assets: List<LocalLlmManager.ModelAsset>,
        ): List<String> =
            assets.filter { llmStateFor(dir, it) == LocalPackState.PRESENT }.map { it.id }

        /**
         * Single-active LLM fallback after deleting [deletedId] when
         * [selectedId] was active: first other installed asset, else
         * the manifest default (server chain until anything verifies).
         * Never a dead selection. Pure for unit tests.
         */
        @JvmStatic
        internal fun llmFallbackAfterDelete(
            dir: File,
            assets: List<LocalLlmManager.ModelAsset>,
            deletedId: String,
            selectedId: String,
        ): String = LocalModelDownload.fallbackAfterDelete(
            deletedId,
            selectedId,
            llmInstalledIds(dir, assets),
            llmDefaultId(assets),
        )

        /**
         * Fail-closed gate: a Local entry may only be switched on with
         * a verified pack. Pure for unit tests.
         */
        @JvmStatic
        internal fun localToggleAllowed(state: LocalPackState): Boolean =
            state == LocalPackState.PRESENT

        /**
         * Local LLM self-test gate: the in-row "Run test" goes live
         * only with a verified pack on a runner build. Fail-closed:
         * missing/mismatch/incomplete or lite (no runner) stays disabled.
         * Pure for unit tests (runner presence + pack injected).
         */
        @JvmStatic
        internal fun llmSelfTestAllowed(
            runnerSupported: Boolean,
            packState: LocalPackState,
        ): Boolean = runnerSupported && packState == LocalPackState.PRESENT

        /**
         * Whether the Local LLM self-test is offered at all. The
         * on-device generate bridge (MediaPipe runner) is absent on
         * lite builds and JVM tests: a test that cannot pass is not
         * offered — the row shows the honest unavailable reason
         * instead of a FAIL that means nothing. Pure for unit tests.
         */
        @JvmStatic
        internal fun llmSelfTestOffered(runnerSupported: Boolean): Boolean =
            runnerSupported

        // -- Provider tests: ONE pattern for every chain row ---------

        /**
         * Local STT test gate: same rule as the LLM self-test
         * (verified pack on a runner build). Delegates to
         * [ProviderTest.localTestAllowed]. Pure for unit tests.
         */
        @JvmStatic
        internal fun sttLocalTestAllowed(
            runnerSupported: Boolean,
            packState: LocalPackState,
        ): Boolean = ProviderTest.localTestAllowed(
            runnerSupported,
            packState == LocalPackState.PRESENT,
        )

        /** Server STT row test goes live when the config validates. Pure. */
        @JvmStatic
        internal fun sttServerTestAllowed(kind: String, url: String, model: String): Boolean =
            ProviderTest.sttServerTestAllowed(kind, url, model)

        /** Remote LLM row test goes live when the config validates. Pure. */
        @JvmStatic
        internal fun llmRemoteTestAllowed(kind: String, url: String, model: String): Boolean =
            ProviderTest.llmRemoteTestAllowed(kind, url, model)

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
            incomplete: Boolean = false,
        ): GatedRowState {
            if (!runnerSupported) return GatedRowState.NEED_FULL_FLAVOR
            return when (
                LocalModelDownload.resolve(packPresent, packMismatch, downloading, error, incomplete)
            ) {
                LocalModelDownload.RowState.MISSING -> GatedRowState.MISSING
                LocalModelDownload.RowState.DOWNLOADING,
                LocalModelDownload.RowState.INCOMPLETE,
                -> GatedRowState.DOWNLOADING
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

        // -- Local sections UX (collapsible + unified STT/LLM pattern) ---

        /** Notification tap target extra (see [tabForDownloadTarget]). */
        internal const val EXTRA_DL_TARGET = "mw_dl_target"
        internal const val DL_TARGET_STT = "stt"
        internal const val DL_TARGET_LLM = "llm"

        /**
         * Root tab opened by a download-notification tap: LLM packs
         * land on Text cleanup (index 2), everything else (null,
         * unknown, STT) on Transcription (index 1). Matches the
         * [Root] tab order. Pure for unit tests.
         */
        @JvmStatic
        internal fun tabForDownloadTarget(target: String?): Int =
            if (target == DL_TARGET_LLM || target == ModelDownloadSchedule.TARGET_LLM) 2 else 1

        /**
         * Tab after a deep-link tap: a pending target wins (repeat taps
         * on the same target re-route identically); null keeps the
         * user's current tab. The caller consumes the pending value
         * (sets it null) right after routing so the next tap is a key
         * change, never swallowed. Pure for unit tests.
         */
        @JvmStatic
        internal fun deepLinkTargetTab(pendingTab: Int?, currentTab: Int): Int =
            pendingTab ?: currentTab

        /**
         * One-shot deep-link seeding for [onCreate]: a fresh start
         * routes to the notification's target; a recreation (rotation,
         * config change, process-death restore) seeds null so the
         * `rememberSaveable` tab restores instead of being yanked back
         * to the tap target on every recreation. Pure for unit tests.
         */
        @JvmStatic
        internal fun seedDeepLinkTab(hasSavedState: Boolean, target: String?): Int? =
            if (hasSavedState) null else target?.let(::tabForDownloadTarget)

        /**
         * Persisted expansion flags for the on-device model sections.
         */
        internal const val PREF_STT_LOCAL_EXPANDED = "local_stt_expanded"
        internal const val PREF_LLM_LOCAL_EXPANDED = "local_llm_expanded"

        /**
         * Selected on-device LLM asset id (`mw_state`
         * SharedPreferences; STT uses
         * [LocalSttModels.PREF_SELECTED_MODEL]). Absent/unknown
         * resolves to the first manifest asset (never a dead Local
         * selection); the choice persists across restarts.
         */
        internal const val PREF_SELECTED_LLM = "local_llm_model"

        /**
         * Collapsed by default: a missing pref reads as collapsed so the
         * on-device model cards stay out of the way on first run.
         * Pure for unit tests (pref value injected).
         */
        @JvmStatic
        internal fun localSectionExpanded(prefSet: Boolean?): Boolean = prefSet ?: false

        /** Default expansion for on-device model sections: collapsed. */
        @JvmStatic
        internal fun localSectionDefaultExpanded(): Boolean = false

        /**
         * Collapsible header summary line, e.g. `moonshine tiny-en ·
         * enabled`. The enabled/disabled words come from resources
         * ([R.string.local_state_enabled]/[R.string.local_state_disabled])
         * so es renders localized; this stays pure string templating.
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun localSectionSummary(
            modelLabel: String,
            enabled: Boolean,
            enabledWord: String = "enabled",
            disabledWord: String = "disabled",
        ): String = "$modelLabel · ${if (enabled) enabledWord else disabledWord}"

        /**
         * Speech counts as configured when the server chain does OR an
         * enabled Local entry has a verified pack. JSON extraction stays
         * at the call site (org.json is stubbed in JVM unit tests);
         * this owns the counting rule. Pure for unit tests.
         */
        @JvmStatic
        internal fun isSpeechConfigured(
            serverConfigured: Boolean,
            localEnabled: Boolean,
            localVerified: Boolean,
        ): Boolean = serverConfigured || (localEnabled && localVerified)

        /**
         * Canonical Local chain-row block order. SttScreen and
         * CleanupScreen render the identical [LocalChainRow] in this
         * order: title, summary, pack status, using-model line, then the
         * enable toggle, Why? explainer and delete. Download, progress
         * and delete-confirm live in the collapsible on-device section,
         * never in the row. Pure for unit tests.
         */
        @JvmStatic
        internal fun localChainRowBlocks(): List<String> =
            listOf("title", "summary", "status", "using_model", "toggle", "why", "delete")

        /** STT Local chain row follows [localChainRowBlocks]. */
        @JvmStatic
        internal fun sttLocalRowBlocks(): List<String> = localChainRowBlocks()

        /** LLM Local chain row follows [localChainRowBlocks]. */
        @JvmStatic
        internal fun llmLocalRowBlocks(): List<String> = localChainRowBlocks()

        // -- Local singleton (one Local entry per list, fixed name) ---

        /** Fixed display name for every Local chain entry. Never configurable. */
        internal const val LOCAL_ENTRY_NAME = "Local"

        /** True when this kind is the on-device Local entry. Pure for unit tests. */
        @JvmStatic
        internal fun isLocalKind(kind: String): Boolean = kind == "Local"

        /**
         * Name field lock: when the kind picker is Local the name is
         * fixed to [LOCAL_ENTRY_NAME] (disabled, not editable).
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun localNameLocked(kind: String): Boolean = kind == "Local"

        /**
         * Canonical name on save: Local-kind rows always persist as
         * [LOCAL_ENTRY_NAME]; other kinds keep their typed name.
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun normalizedEntryName(kind: String, name: String): String =
            if (kind == "Local") LOCAL_ENTRY_NAME else name

        /**
         * True when a stored Local-kind row needs its rename migration
         * (existing non-'Local' name -> [LOCAL_ENTRY_NAME] on save/migrate).
         * Pure for unit tests.
         */
        @JvmStatic
        internal fun needsLocalRename(kind: String, name: String): Boolean =
            kind == "Local" && name != LOCAL_ENTRY_NAME

        /** True when the list already holds a Local entry. Pure for unit tests. */
        @JvmStatic
        internal fun hasLocalEntry(kinds: List<String>): Boolean =
            kinds.any { it == "Local" }

        /**
         * Add-flow singleton guard: saving [newKind] is blocked when it
         * is Local and another Local already exists
         * ([existingKindsExcludingSelf] = current list kinds minus the
         * row being edited). Pure for unit tests.
         */
        @JvmStatic
        internal fun localSingletonBlocked(
            existingKindsExcludingSelf: List<String>,
            newKind: String,
        ): Boolean = newKind == "Local" && existingKindsExcludingSelf.contains("Local")

        /**
         * Edit-dialog kind-picker guard: editing a Local entry hides the
         * kind picker (no change-away footgun). Same pattern on STT and
         * LLM screens; [originalKind] is the stored kind when the dialog
         * opened. Pure for unit tests.
         */
        @JvmStatic
        internal fun localEditHidesKindPicker(originalKind: String): Boolean =
            originalKind == "Local"

        /**
         * Canonical on-device model card block order (STT per-model
         * cards and the LLM on-device card alike): title, size, model
         * picker (Use buttons / asset dropdown), badges/notes, pack
         * status, dest path, progress bar, download/retry/delete
         * actions. Pure for unit tests.
         */
        @JvmStatic
        internal fun localModelCardBlocks(): List<String> =
            listOf("title", "size", "use", "badges", "status", "dest", "progress", "actions")

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
        // One-shot: recreations restore the saved tab (seed null),
        // only a fresh start routes to the notification target.
        deepLinkTab.value = seedDeepLinkTab(
            savedInstanceState != null,
            intent?.getStringExtra(EXTRA_DL_TARGET),
        )
        refreshPermStates()
        reload()
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Warm tap (singleTop): route to the tapped download's page.
        intent.getStringExtra(EXTRA_DL_TARGET)?.let {
            deepLinkTab.value = tabForDownloadTarget(it)
        }
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
     * Track one background pack download in a model row. The
     * Download/Retry buttons enqueue via [ModelDownloadManager] (bytes
     * move in WorkManager, app-open not required); this observer feeds
     * the row's in-place progress + terminal states back from the
     * unique work's LiveData. State → action mapping is
     * [ModelDownloadSchedule.actionForWorkState] (unit-tested):
     * waiting/running paints progress, SUCCEEDED refreshes the
     * SHA-verified pack derivation, FAILED pins the worker's detail,
     * CANCELLED clears without pinning. One observer per work name;
     * terminal states self-remove. Never throws.
     */
    private fun observeDownloadWork(
        kind: String,
        id: String,
        onProgress: (receivedBytes: Long, totalBytes: Long?) -> Unit,
        onDone: () -> Unit,
        onError: (detail: String) -> Unit,
    ) {
        val workName = ModelDownloadSchedule.workNameFor(kind, id)
        if (dlWorkObservers.containsKey(workName)) return
        try {
            val live = WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(workName)
            val obs = Observer<List<WorkInfo>> { infos ->
                val info = infos.firstOrNull() ?: return@Observer
                when (ModelDownloadSchedule.actionForWorkState(info.state.name)) {
                    ModelDownloadSchedule.WorkRowAction.TRACK -> {
                        val r = info.progress.getLong(ModelDownloadSchedule.PROG_RECEIVED, -1L)
                        if (r >= 0) {
                            val t = info.progress.getLong(ModelDownloadSchedule.PROG_TOTAL, -1L)
                            onProgress(r, t.takeIf { it >= 0 })
                        }
                    }
                    ModelDownloadSchedule.WorkRowAction.DONE,
                    ModelDownloadSchedule.WorkRowAction.CLEARED,
                    -> {
                        dlWorkObservers.remove(workName)?.let { live.removeObserver(it) }
                        onDone()
                    }
                    ModelDownloadSchedule.WorkRowAction.FAILED -> {
                        dlWorkObservers.remove(workName)?.let { live.removeObserver(it) }
                        val detail = info.outputData
                            .getString(ModelDownloadSchedule.OUTPUT_ERROR)
                            .takeUnless { it.isNullOrEmpty() } ?: "failed"
                        onError(detail)
                    }
                }
            }
            dlWorkObservers[workName] = obs
            live.observe(this, obs)
        } catch (_: Exception) {
        }
    }

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
            var tab by rememberSaveable { mutableIntStateOf(deepLinkTab.value ?: 0) }
            // Notification deep-link: a warm tap re-routes the open app.
            // Consumed after routing (set null): without this a re-tap
            // on the same target is a no-op key change and gets ignored.
            LaunchedEffect(deepLinkTab.value) {
                val pending = deepLinkTab.value
                if (pending != null) {
                    tab = deepLinkTargetTab(pending, tab)
                    deepLinkTab.value = null
                }
            }
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

    /**
     * Collapsible on-device model section (STT + LLM share this): a
     * header card with title, summary line (e.g. `moonshine tiny-en ·
     * enabled`) and chevron, collapsed by default with the expansion
     * persisted under [prefKey] ([PREF_STT_LOCAL_EXPANDED] /
     * [PREF_LLM_LOCAL_EXPANDED]). The expanded body holds the model
     * cards in [localModelCardBlocks] order.
     */
    @Composable
    private fun CollapsibleModelsSection(
        title: String,
        summary: String,
        prefKey: String,
        body: @Composable () -> Unit,
    ) {
        val prefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        var expanded by remember {
            mutableStateOf(prefs.getBoolean(prefKey, localSectionDefaultExpanded()))
        }
        fun toggle() {
            expanded = !expanded
            prefs.edit { putBoolean(prefKey, expanded) }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { toggle() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(summary, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { toggle() }) {
                        // Text chevron (icons-core has no expand glyphs):
                        // TalkBack reads the expanded/collapsed state.
                        Text(if (expanded) "▲" else "▼")
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    body()
                }
            }
        }
    }

    /**
     * The ONE Local chain-row pattern ([localChainRowBlocks] order):
     * title, summary, pack status, using-model line, then the enable
     * toggle, Why? explainer and delete. Used identically by the STT
     * and LLM chains; download / progress / delete-confirm live in
     * the collapsible on-device section, never in the row.
     */
    @Composable
    private fun LocalChainRow(
        e: JSONObject,
        summary: String,
        statusText: String,
        statusOk: Boolean,
        usingModelText: String,
        onToggle: (Boolean) -> Unit,
        onWhy: () -> Unit,
        onDelete: () -> Unit,
        handle: @Composable () -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.padding(end = 4.dp)) { handle() }
                Column(modifier = Modifier.weight(1f)) {
                    Text(e.optString("name", "entry"))
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        usingModelText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = e.optBoolean("enabled", true),
                    onCheckedChange = onToggle,
                )
                TextButton(onClick = onWhy) { Text(stringResource(R.string.stt_local_why)) }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        }
    }

    /**
     * THE provider-test affordance: one button, same placement
     * under every chain row (STT + LLM, local + remote) and under
     * every add/edit dialog form, same inline reporting (`latency +
     * excerpt/status`). Disabled fail-closed via [enabled]; busy shows
     * the testing copy; a disabled button shows [disabledReason] so
     * the blocker stays explicit even under a stale result
     * ([ProviderTest.showDisabledReason]).
     */
    @Composable
    private fun ProviderTestRow(
        busy: Boolean,
        result: ProviderTest.Result?,
        enabled: Boolean,
        onTest: () -> Unit,
        disabledReason: String? = null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ONE shared test-button style (min height + padding
                // identical to the Add buttons): every chain row,
                // dialog, and self-test renders through here.
                ProviderTestButton(
                    enabled = enabled,
                    busy = busy,
                    onTest = onTest,
                )
                Spacer(Modifier.width(8.dp))
                if (busy && result == null) {
                    Text(
                        stringResource(R.string.provider_test_testing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (result != null) {
                    Text(
                        if (result.passed) {
                            stringResource(
                                R.string.provider_test_pass,
                                result.latencyMs,
                                result.excerpt,
                            )
                        } else {
                            stringResource(
                                R.string.provider_test_fail,
                                result.latencyMs,
                                result.excerpt.ifBlank { result.error ?: "failed" },
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.passed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            val reason = disabledReason
            if (reason != null && ProviderTest.showDisabledReason(enabled, busy, reason)) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * Lightweight endpoint check (no heavy transcribe/generate):
     * any HTTP response = reachable (even 404/405 on POST-only
     * endpoints); only network throws read as FAIL. Short timeouts.
     * Never touches engines/runners/services.
     */
    private fun httpGetCode(urlStr: String, timeoutMs: Int = 8000): Int {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.connect()
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** GET body snippet for model-list checks (Ollama `/api/tags`). */
    private fun httpGetBody(urlStr: String, timeoutMs: Int = 8000, maxChars: Int = 4096): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("http $code")
            return conn.inputStream.bufferedReader().use { it.readText().take(maxChars) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * THE server-probe implementations shared by chain rows and
     * add/edit dialogs (one pattern, one result shape): lightweight
     * endpoint checks, never a heavy transcribe/generate. STT: any
     * HTTP response = reachable; `ws(s)` / blank vendor-default URL
     * has no HTTP surface so config validation stands in. LLM: blank
     * URL = config validation; Ollama checks model reachability via
     * GET /api/tags where cheap; Cloud does the lightweight GET.
     * Never touches engines/runners/services.
     */
    private fun probeSttEndpoint(url: String, model: String): ProviderTest.Result {
        val u = url.trim()
        val m = model.trim()
        if (u.isEmpty() || u.startsWith("ws", ignoreCase = true)) {
            return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
                "config ok · $m"
            }
        }
        return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
            val code = httpGetCode(u)
            "HTTP $code · $m"
        }
    }

    private fun probeLlmEndpoint(kind: String, url: String, model: String): ProviderTest.Result {
        val u = url.trim()
        val m = model.trim()
        if (u.isEmpty()) {
            return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
                "config ok · $m"
            }
        }
        if (kind == "Ollama") {
            return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
                val base = u.trimEnd('/')
                val body = httpGetBody("$base/api/tags")
                if (!body.contains(m, ignoreCase = true)) {
                    throw IllegalStateException("reachable · model not listed")
                }
                "model ok · $m"
            }
        }
        return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
            val code = httpGetCode(u)
            "HTTP $code · $m"
        }
    }

    /**
     * Disabled-reason copy for a server STT row/dialog (null when the
     * test is live). Maps the shared [ProviderTest.validateStt] rule
     * to the existing provider-test strings. Context access stays at
     * the call site (this is a member for getString).
     */
    private fun sttTestDisabledReason(kind: String, url: String, model: String): String? =
        when (ProviderTest.validateStt(kind, url, model)) {
            ProviderTest.SttError.NONE -> null
            ProviderTest.SttError.NEED_URL -> getString(R.string.provider_test_need_url)
            ProviderTest.SttError.NEED_MODEL -> getString(R.string.provider_test_need_model)
            ProviderTest.SttError.BAD_URL -> getString(R.string.provider_test_bad_url)
            ProviderTest.SttError.BAD_BASE -> getString(R.string.provider_test_bad_base)
        }

    /** Same as [sttTestDisabledReason] for remote LLM rows/dialogs. */
    private fun llmTestDisabledReason(kind: String, url: String, model: String): String? =
        when (ProviderTest.validateLlm(kind, url, model)) {
            ProviderTest.LlmError.NONE -> null
            ProviderTest.LlmError.NEED_URL -> getString(R.string.provider_test_need_url)
            ProviderTest.LlmError.NEED_MODEL -> getString(R.string.provider_test_need_model)
            ProviderTest.LlmError.BAD_URL -> getString(R.string.provider_test_bad_base)
        }

    /**
     * Disabled-reason copy for the on-device (Local STT / LLM
     * self-test) buttons: no runner/bridge first, then the missing
     * pack; null when live. Member for getString; the rule itself is
     * [ProviderTest.localTestBlocked] (unit-tested).
     */
    private fun localTestDisabledReason(runnerSupported: Boolean, packVerified: Boolean): String? =
        when (ProviderTest.localTestBlocked(runnerSupported, packVerified)) {
            ProviderTest.LocalBlocked.NONE -> null
            ProviderTest.LocalBlocked.NEED_RUNNER -> getString(R.string.local_need_full)
            ProviderTest.LocalBlocked.NEED_PACK -> getString(R.string.provider_test_need_pack)
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
            // A verified on-device pack counts as configured:
            // Local-only setups must not report "no speech configured".
            val setupPrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
            val localEnabled = remember(s) {
                val arr = s.optJSONObject("stt")?.optJSONArray("entries") ?: JSONArray()
                var found = false
                for (i in 0 until arr.length()) {
                    val en = arr.optJSONObject(i) ?: continue
                    if (en.optString("kind") == "Local" && en.optBoolean("enabled", true)) {
                        found = true
                        break
                    }
                }
                found
            }
            val localVerified = remember(s) {
                try {
                    sttModelPackState(LocalStt.selectedEntry(setupPrefs)) == LocalPackState.PRESENT
                } catch (_: Exception) {
                    false
                }
            }
            val serverDone = isSpeechConfigured(isServerConfigured(s), localEnabled, localVerified)
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
                    (if (serverDone) "✓ " else "○ ") + stringResource(R.string.setup_speech_configured),
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

    /** Row state to chain-row pack state (1:1; DOWNLOADING/FAILED never persist here). */
    private fun packStateForRow(row: LocalModelDownload.RowState): LocalPackState =
        when (row) {
            LocalModelDownload.RowState.PRESENT -> LocalPackState.PRESENT
            LocalModelDownload.RowState.MISMATCH -> LocalPackState.MISMATCH
            LocalModelDownload.RowState.INCOMPLETE -> LocalPackState.INCOMPLETE
            else -> LocalPackState.MISSING
        }

    /** Pack state for one catalog model (SHA-verified, single-pass). */
    private fun sttModelPackState(model: LocalSttModels.Entry): LocalPackState =
        try {
            packStateForRow(LocalSttModels.stateFor(filesDir, model))
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
        // One inspect per model per save-generation (state + verified
        // bytes from a single SHA pass — never two full hash sweeps).
        val modelInspects = remember(version) {
            LocalSttModels.MODELS.associate { m ->
                m.id to try {
                    LocalSttModels.inspect(filesDir, m)
                } catch (_: Exception) {
                    LocalSttModels.PackInspect(LocalModelDownload.RowState.MISSING, 0L)
                }
            }
        }
        fun packOf(id: String): LocalPackState =
            modelInspects[id]?.let { packStateForRow(it.state) } ?: LocalPackState.MISSING
        fun bytesOf(id: String): Long =
            modelInspects[id]?.verifiedBytes ?: 0L
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
                packOf(m.id) == LocalPackState.INCOMPLETE,
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
            // Background path: bytes move in WorkManager (app-open not
            // required, own progress notification + Cancel); the row
            // tracks the unique work below for in-place progress.
            ModelDownloadManager.enqueueSttModel(this@MainActivity, m.id)
            observeDownloadWork(
                kind = ModelDownloadSchedule.KIND_STT,
                id = m.id,
                onProgress = { r, t ->
                    dlProg = dlProg + (m.id to (r to (t ?: LocalSttModels.totalBytes(m))))
                },
                onDone = { dlBusy = dlBusy - m.id; version++ },
                onError = { msg ->
                    dlBusy = dlBusy - m.id
                    dlErr = dlErr + (m.id to msg)
                    version++
                },
            )
        }
        // Provider tests: ONE pattern on every chain row (same
        // button + latency/excerpt inline). Per-entry state keyed by
        // row id so reorder keeps results with their row.
        var sttLocalBusy by remember { mutableStateOf(false) }
        var sttLocalResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        var sttBusy by remember { mutableStateOf(setOf<String>()) }
        var sttResults by remember { mutableStateOf(mapOf<String, ProviderTest.Result>()) }
        fun probeSttServer(url: String, model: String): ProviderTest.Result =
            probeSttEndpoint(url, model)
        fun startSttServerTest(e: JSONObject) {
            val id = e.optString("id")
            if (id.isBlank() || sttBusy.contains(id)) return
            val kind = e.optString("kind")
            val url = e.optString("url")
            val model = e.optString("model")
            if (!sttServerTestAllowed(kind, url, model)) return
            sttBusy = sttBusy + id
            sttResults = sttResults - id
            workers.execute {
                val result = try {
                    probeSttServer(url, model)
                } catch (ex: Exception) {
                    ProviderTest.mapServerSttResult(
                        null, 0L, ProviderTest.SERVER_TIMEOUT_MS,
                        ex.message ?: "failed",
                    )
                }
                runOnUiThread {
                    sttBusy = sttBusy - id
                    sttResults = sttResults + (id to result)
                }
            }
        }
        fun startSttLocalTest() {
            // Fail-closed: verified pack on a runner build only.
            if (!sttLocalTestAllowed(sttRunner, selPack)) return
            if (sttLocalBusy) return
            sttLocalBusy = true
            sttLocalResult = null
            workers.execute {
                val result = try {
                    ProviderTest.runProbe(ProviderTest.LOCAL_TIMEOUT_MS) {
                        val r = LocalStt.transcribe(
                            this, ProviderTest.samplePcm16(), 16000,
                            stt.optString("language", "auto").ifBlank { "auto" },
                        ) ?: throw IllegalStateException("decode skipped — engine unavailable")
                        val text = r.text.trim()
                        if (text.isNotBlank()) text
                        else "pack ok · ${r.chunks} chunks · ${r.decodeMs}ms"
                    }
                } catch (ex: Exception) {
                    ProviderTest.mapLocalSttResult(
                        null, 0L, ProviderTest.LOCAL_TIMEOUT_MS,
                        ex.message ?: "failed",
                    )
                }
                runOnUiThread {
                    sttLocalResult = result
                    sttLocalBusy = false
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") version
        // Singleton rename-migrate (STT): stored Local-kind rows with a
        // custom name are renamed to "Local" once per save-generation.
        LaunchedEffect(version) {
            val arr = entriesOf(stt, "entries")
            var fixed = false
            for (i in 0 until arr.length()) {
                val en = arr.optJSONObject(i) ?: continue
                if (needsLocalRename(en.optString("kind"), en.optString("name"))) {
                    en.put("name", LOCAL_ENTRY_NAME)
                    fixed = true
                }
            }
            if (fixed) {
                save()
                version++
            }
        }
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
                    if (isLocal) {
                        // Local follows the ONE chain-row pattern
                        // ([localChainRowBlocks]): title, summary, pack
                        // status, using-model line, toggle, Why?,
                        // delete. Downloads live in the collapsible
                        // on-device section below, never in the row.
                        // Lite flavor (no runner): full-APK pointer
                        // instead of pack status.
                        val localStatus = if (!sttRunner) {
                            getString(R.string.local_need_full)
                        } else {
                            when (selPack) {
                                LocalPackState.PRESENT ->
                                    getString(R.string.local_pack_present)
                                LocalPackState.MISSING ->
                                    getString(R.string.local_pack_missing)
                                LocalPackState.MISMATCH ->
                                    getString(R.string.local_pack_mismatch)
                                LocalPackState.INCOMPLETE ->
                                    getString(
                                        R.string.local_downloading,
                                        selectedModel.name,
                                    )
                            }
                        }
                        LocalChainRow(
                            e = e,
                            summary = entrySummary(e),
                            statusText = localStatus,
                            statusOk = sttRunner && selPack == LocalPackState.PRESENT,
                            usingModelText = getString(
                                R.string.stt_using_model,
                                selectedModel.name,
                            ),
                            onToggle = {
                                onLocalToggle(e, it, selPack, { version++ }, sttRunner)
                            },
                            onWhy = { localInfo = true },
                            onDelete = {
                                val next = JSONArray()
                                for (j in 0 until entries.length()) {
                                    if (entries.optJSONObject(j)?.optString("id") != e.optString("id")) {
                                        next.put(entries.get(j))
                                    }
                                }
                                stt.put("entries", next); save(); version++
                            },
                            handle = handle,
                        )
                        // Local STT test (decode sample), same ONE
                        // row pattern as every provider row.
                        ProviderTestRow(
                            busy = sttLocalBusy,
                            result = sttLocalResult,
                            enabled = sttLocalTestAllowed(sttRunner, selPack),
                            onTest = { startSttLocalTest() },
                            disabledReason = localTestDisabledReason(
                                sttRunner,
                                selPack == LocalPackState.PRESENT,
                            ),
                        )
                    } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.padding(end = 4.dp)) { handle() }
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
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
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
                        // Server STT test (lightweight endpoint
                        // check, no transcribe), same ONE pattern.
                        val sttId = e.optString("id")
                        ProviderTestRow(
                            busy = sttBusy.contains(sttId),
                            result = sttResults[sttId],
                            enabled = sttServerTestAllowed(
                                e.optString("kind"),
                                e.optString("url"),
                                e.optString("model"),
                            ),
                            onTest = { startSttServerTest(e) },
                            disabledReason = sttTestDisabledReason(
                                e.optString("kind"),
                                e.optString("url"),
                                e.optString("model"),
                            ),
                        )
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
            // Collapsed by default (persisted): the model cards stay
            // out of the way until the owner expands them.
            val sttLocalOn = run {
                val arr = entriesOf(stt, "entries")
                var on = false
                for (i in 0 until arr.length()) {
                    val en = arr.optJSONObject(i) ?: continue
                    if (en.optString("kind") == "Local") {
                        on = en.optBoolean("enabled", true)
                        break
                    }
                }
                on
            }
            CollapsibleModelsSection(
                title = stringResource(R.string.stt_models_title),
                summary = localSectionSummary(
                    selectedModel.name,
                    sttLocalOn,
                    getString(R.string.local_state_enabled),
                    getString(R.string.local_state_disabled),
                ),
                prefKey = PREF_STT_LOCAL_EXPANDED,
            ) {
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
                // Device total RAM for the flagship gate note (display only).
                val deviceRamMb = remember { totalRamMb() }
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
                                        // Packs coexist: selecting never
                                        // removes others. A missing pack
                                        // just needs Download — the server
                                        // chain covers it until verified.
                                        if (LocalModelDownload.selectNeedsDownload(row)) {
                                            toast(
                                                getString(
                                                    R.string.local_selected_needs_download,
                                                    m.name,
                                                ),
                                            )
                                        }
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
                            // Measured speed: last-decode ×realtime on
                            // THIS phone (per-model float in mw_state),
                            // else the neutral pre-first-run copy. No
                            // absolute speed adjectives anywhere
                            // (owner phone-tested). Display only.
                            run {
                                val stored = try {
                                    val key = LocalSttModels.speedPrefKey(m.id)
                                    if (statePrefs.contains(key)) {
                                        statePrefs.getFloat(key, Float.NaN)
                                    } else {
                                        Float.NaN
                                    }
                                } catch (_: Exception) {
                                    Float.NaN
                                }
                                Text(
                                    LocalSttModels.storedSpeedFactor(stored)?.let { factor ->
                                        getString(R.string.stt_speed_measured, factor)
                                    } ?: stringResource(R.string.stt_speed_unmeasured),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Flagship RAM gate note (medium tier only):
                            // requires-flagship copy with this phone's
                            // total RAM. Display only; enablement stays
                            // fail-closed on the verified pack.
                            if (m.minTotalRamMb > 0) {
                                Text(
                                    if (deviceRamMb > 0) {
                                        getString(
                                            R.string.stt_ram_gate,
                                            m.minTotalRamMb,
                                            deviceRamMb,
                                        )
                                    } else {
                                        getString(
                                            R.string.stt_ram_gate_unknown,
                                            m.minTotalRamMb,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                                    LocalModelDownload.RowState.DOWNLOADING,
                                    LocalModelDownload.RowState.INCOMPLETE,
                                    -> {
                                        // DOWNLOADING = live worker progress;
                                        // INCOMPLETE = interrupted set, resume from
                                        // verified bytes already on disk.
                                        val total = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                            prog?.second ?: 0L
                                        } else {
                                            LocalSttModels.totalBytes(m)
                                        }
                                        val received = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                            prog?.first ?: 0L
                                        } else {
                                            bytesOf(m.id)
                                        }
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
                            if (row == LocalModelDownload.RowState.DOWNLOADING ||
                                row == LocalModelDownload.RowState.INCOMPLETE
                            ) {
                                Spacer(Modifier.height(4.dp))
                                val frac = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                    LocalModelDownload.progressFraction(
                                        prog?.first ?: 0L,
                                        prog?.second?.takeIf { it > 0 },
                                    )
                                } else {
                                    LocalModelDownload.progressFraction(
                                        bytesOf(m.id),
                                        LocalSttModels.totalBytes(m).takeIf { it > 0 },
                                    )
                                }
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
                                    LocalModelDownload.RowState.INCOMPLETE -> {
                                        // Interrupted set: Resume picks up where
                                        // the bytes stopped (verified files are
                                        // skipped, never re-downloaded).
                                        OutlinedButton(onClick = { startModelDownload(m) }) {
                                            Text(stringResource(R.string.local_resume))
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
                                LocalPackState.INCOMPLETE ->
                                    stringResource(
                                        R.string.local_downloading,
                                        selectedModel.name,
                                    )
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
                        // Snapshot selection now (composable state, UI thread).
                        val wasSelected = doomed.id == selectedModel.id
                        val presentAfter = LocalSttModels.MODELS
                            .filter { it.id != doomed.id && packOf(it.id) == LocalPackState.PRESENT }
                            .map { it.id }
                        workers.execute {
                            try { LocalSttModels.delete(filesDir, doomed) } catch (_: Exception) {}
                            runOnUiThread {
                                dlErr = dlErr - doomed.id
                                dlBusy = dlBusy - doomed.id
                                if (wasSelected) {
                                    // Deleting the active pack reselects
                                    // the first remaining verified pack
                                    // (single-active rule); with nothing
                                    // left the default id stays selected
                                    // and the server chain covers Local —
                                    // never a dead Local selection.
                                    // setSelectedEntry persists + unloads
                                    // the cached recognizer.
                                    val next = LocalModelDownload.fallbackAfterDelete(
                                        doomed.id,
                                        doomed.id,
                                        presentAfter,
                                        LocalSttModels.DEFAULT_ID,
                                    )
                                    LocalStt.setSelectedEntry(statePrefs, next)
                                    if (presentAfter.contains(next)) {
                                        toast(
                                            getString(
                                                R.string.local_deleted_active,
                                                doomed.name,
                                                LocalSttModels.findById(next).name,
                                            ),
                                        )
                                    } else {
                                        toast(
                                            getString(
                                                R.string.local_deleted_active_none,
                                                doomed.name,
                                            ),
                                        )
                                    }
                                } else {
                                    toast(getString(R.string.local_deleted))
                                }
                                version++
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
        dialog?.let {
            EntryDialog(
                getString(R.string.stt_dialog_title),
                it,
                onClose = { dialog = null },
                onSave = { edited ->
                    upsertEntry(stt, "entries", edited)
                    save(); version++; dialog = null
                },
                hasOtherLocal = run {
                    val arr = entriesOf(stt, "entries")
                    val id = it.optString("id")
                    var found = false
                    for (i in 0 until arr.length()) {
                        val en = arr.optJSONObject(i) ?: continue
                        if (en.optString("kind") == "Local" && en.optString("id") != id) {
                            found = true
                            break
                        }
                    }
                    found
                },
            )
        }
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
        hasOtherLocal: Boolean = false,
    ) {
        val originalKind = remember { e.optString("kind", "SelfHosted") }
        val isLocalEdit = remember { localEditHidesKindPicker(originalKind) }
        var name by remember { mutableStateOf(e.optString("name")) }
        var kind by remember {
            mutableStateOf(
                e.optString("kind", "SelfHosted").takeIf {
                    it == "Cloud" || it == "Local"
                } ?: "SelfHosted",
            )
        }
        val isLocalKind = kind == "Local"
        val nameLocked = localNameLocked(kind)
        val singletonBlocked = hasOtherLocal && isLocalKind && !isLocalEdit
        var url by remember { mutableStateOf(e.optString("url")) }
        var model by remember { mutableStateOf(e.optString("model")) }
        var provider by remember { mutableStateOf(e.optString("provider", "OpenAI")) }
        var key by remember { mutableStateOf(e.optString("api_key")) }
        var streaming by remember { mutableStateOf(e.optBoolean("streaming", true)) }
        var enabled by remember { mutableStateOf(e.optBoolean("enabled", true)) }
        var showKey by remember { mutableStateOf(false) }
        // Draft test: same ONE ProviderTestRow pattern as the chain
        // rows (same button + latency/excerpt inline), run against the
        // unsaved draft values. Draft edits clear the stale result.
        var draftBusy by remember { mutableStateOf(false) }
        var draftResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        val urlRequired = kind == "SelfHosted"
        val urlBlank = url.isBlank() && urlRequired
        val modelBlank = model.isBlank() && !isLocalKind
        val canSave = !urlBlank && !modelBlank && !singletonBlocked
        fun startDraftTest() {
            if (!sttServerTestAllowed(kind, url, model) || draftBusy) return
            draftBusy = true
            draftResult = null
            val u = url
            val m = model
            workers.execute {
                val result = try {
                    probeSttEndpoint(u, m)
                } catch (ex: Exception) {
                    ProviderTest.mapServerSttResult(
                        null, 0L, ProviderTest.SERVER_TIMEOUT_MS,
                        ex.message ?: "failed",
                    )
                }
                runOnUiThread {
                    draftBusy = false
                    draftResult = result
                }
            }
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("${stringResource(R.string.stt_dialog_title)} · $kind") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = if (nameLocked) LOCAL_ENTRY_NAME else name,
                        onValueChange = { if (!nameLocked) name = it },
                        enabled = !nameLocked,
                        label = { Text(stringResource(R.string.stt_name)) },
                        supportingText = {
                            if (nameLocked) Text(stringResource(R.string.local_name_locked))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!isLocalEdit) {
                        DropDown(getString(R.string.llm_kind), kind, listOf("SelfHosted", "Cloud", "Local")) {
                            kind = it
                            if (it == "Local") name = LOCAL_ENTRY_NAME
                            draftResult = null
                        }
                    }
                    if (singletonBlocked) {
                        Text(
                            stringResource(R.string.local_singleton_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onClose) {
                            Text(stringResource(R.string.local_show_existing))
                        }
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
                                stringResource(R.string.stt_gemini_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (!isLocalKind) {
                        OutlinedTextField(value = url, onValueChange = { url = it; draftResult = null },
                            label = {
                                Text(
                                    if (kind == "Cloud") stringResource(R.string.stt_url_cloud)
                                    else stringResource(R.string.stt_url_server),
                                )
                            },
                            isError = urlBlank,
                            supportingText = {
                                if (urlBlank) Text(stringResource(R.string.provider_test_server_url_required))
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
                        OutlinedTextField(value = model, onValueChange = { model = it; draftResult = null },
                            label = { Text(stringResource(R.string.stt_model)) },
                            isError = modelBlank,
                            supportingText = {
                                if (modelBlank) Text(stringResource(R.string.provider_test_model_required))
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
                                    Text(
                                        if (showKey) stringResource(R.string.stt_apikey_hide)
                                        else stringResource(R.string.stt_apikey_show),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (kind == "SelfHosted") {
                        Text(
                            stringResource(R.string.stt_apikey_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (kind == "SelfHosted") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.stt_streaming), modifier = Modifier.weight(1f))
                            Switch(checked = streaming, onCheckedChange = { streaming = it })
                        }
                        Text(
                            stringResource(R.string.stt_streaming_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stt_enabled), modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    if (!isLocalKind) {
                        // Draft probe: same ONE row pattern (button +
                        // latency/excerpt), same gate as the saved rows.
                        ProviderTestRow(
                            busy = draftBusy,
                            result = draftResult,
                            enabled = sttServerTestAllowed(kind, url, model),
                            onTest = { startDraftTest() },
                            disabledReason = sttTestDisabledReason(kind, url, model),
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
                        // Local-kind rows always persist as "Local" (rename-migrate).
                        e.put("name", normalizedEntryName(kind, name)).put("kind", kind).put("url", url.trim())
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
        val statePrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        // Selected LLM asset (persisted across restarts) + per-asset
        // verify, once per save-generation (not per row or keystroke);
        // enabling Local without the selected asset verified fails
        // closed. Packs coexist (one file per asset); selecting never
        // removes others.
        val assets = remember { llmAssets() }
        var version by remember { mutableIntStateOf(0) }
        val llmSelected = remember(version) {
            llmSelectedAsset(assets, statePrefs.getString(PREF_SELECTED_LLM, null))
        }
        val llmInspects = remember(version) {
            assets.associate { a ->
                a.id to try {
                    llmInspect(filesDir, a)
                } catch (_: Exception) {
                    LlmInspect(LocalPackState.MISSING, 0L)
                }
            }
        }
        fun llmPackOf(id: String): LocalPackState =
            llmInspects[id]?.state ?: LocalPackState.MISSING
        fun llmBytesOf(id: String): Long =
            llmInspects[id]?.verifiedBytes ?: 0L
        val llmSelId = llmSelected?.id
        val llmSelPack = if (llmSelId != null) llmPackOf(llmSelId) else LocalPackState.MISSING
        // Per-asset in-row download state (desktop fill_stt_models
        // parity: progress updates in place, Retry on failure).
        var llmDlBusy by remember { mutableStateOf(setOf<String>()) }
        var llmDlProg by remember { mutableStateOf(mapOf<String, Pair<Long, Long?>>()) }
        var llmDlErr by remember { mutableStateOf(mapOf<String, String>()) }
        var llmDeleteConfirm by remember { mutableStateOf<String?>(null) }
        // Local LLM test (existing self-test harness, now through
        // the ONE [ProviderTest.Result] pattern) + remote LLM probes
        // (minimal ping, no heavy generate). Per-entry remote state
        // keyed by row id.
        var llmTestBusy by remember { mutableStateOf(false) }
        var llmTestResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        var llmBusy by remember { mutableStateOf(setOf<String>()) }
        var llmResults by remember { mutableStateOf(mapOf<String, ProviderTest.Result>()) }
        fun llmRowOf(a: LocalLlmManager.ModelAsset): LocalModelDownload.RowState =
            LocalModelDownload.resolve(
                llmPackOf(a.id) == LocalPackState.PRESENT,
                llmPackOf(a.id) == LocalPackState.MISMATCH,
                llmDlBusy.contains(a.id),
                llmDlErr[a.id],
                llmPackOf(a.id) == LocalPackState.INCOMPLETE,
            )
        /** Verified file backing [a]: per-asset slot, else the adopted legacy slot. */
        fun llmActiveFile(a: LocalLlmManager.ModelAsset): File {
            val slot = llmTaskFileFor(filesDir, a.id)
            if (!isPlaceholderSha(a.sha256) && LocalLlmManager.verifySha256(slot, a.sha256)) {
                return slot
            }
            return LocalLlmManager.defaultTaskFile(filesDir)
        }
        // Lite flavor ships no MediaPipe runner: no Download offered.
        val llmRunner = remember { llmRunnerSupported() }
        fun startLlmDownload(a: LocalLlmManager.ModelAsset) {
            // Lite flavor: downloads could never run — refuse fail-closed.
            if (!llmRunner) {
                toast(getString(R.string.local_need_full))
                return
            }
            if (llmDlBusy.contains(a.id) || isPlaceholderSha(a.sha256)) return
            llmDlBusy = llmDlBusy + a.id
            llmDlErr = llmDlErr - a.id
            llmDlProg = llmDlProg + (a.id to (0L to a.sizeBytes.takeIf { it > 0 }))
            // Background path: bytes move in WorkManager (app-open not
            // required, own progress notification + Cancel); the row
            // tracks the unique work below for in-place progress.
            ModelDownloadManager.enqueueLlmAsset(this@MainActivity, a)
            observeDownloadWork(
                kind = ModelDownloadSchedule.KIND_LLM,
                id = a.id,
                onProgress = { r, t -> llmDlProg = llmDlProg + (a.id to (r to t)) },
                onDone = { llmDlBusy = llmDlBusy - a.id; version++ },
                onError = { msg ->
                    llmDlBusy = llmDlBusy - a.id
                    llmDlErr = llmDlErr + (a.id to msg)
                    version++
                },
            )
        }
        fun selectLlm(a: LocalLlmManager.ModelAsset) {
            statePrefs.edit() { putString(PREF_SELECTED_LLM, a.id) }
            version++
            // A missing pack just needs Download — the chain moves on
            // to Ollama/cloud until its file verifies.
            if (LocalModelDownload.selectNeedsDownload(llmRowOf(a))) {
                toast(getString(R.string.local_selected_needs_download, a.id))
            }
        }
        fun startLlmSelfTest() {
            // Fail-closed: verified pack + runner only; local path
            // only, never Ollama/cloud (no fallback masking). United
            // with every other row via [ProviderTest.fromSelfTest].
            if (!llmSelfTestAllowed(llmRunner, llmSelPack)) return
            val target = llmSelected ?: return
            if (llmTestBusy) return
            llmTestBusy = true
            llmTestResult = null
            workers.execute {
                val result = try {
                    val system = CoreBridge.defaultCleanupPrompt().getOrThrow()
                    ProviderTest.fromSelfTest(
                        LocalLlmManager.runSelfTest(
                            llmActiveFile(target),
                            system,
                        ),
                    )
                } catch (ex: Exception) {
                    ProviderTest.fromSelfTest(
                        LocalLlmManager.mapSelfTestResult(
                            null,
                            0L,
                            LocalLlmManager.SELFTEST_TIMEOUT_MS,
                            ex.message ?: "failed",
                        ),
                    )
                }
                runOnUiThread {
                    llmTestResult = result
                    llmTestBusy = false
                }
            }
        }
        fun probeLlmRemote(kind: String, url: String, model: String): ProviderTest.Result =
            probeLlmEndpoint(kind, url, model)
        fun startLlmRemoteTest(e: JSONObject) {
            val id = e.optString("id")
            if (id.isBlank() || llmBusy.contains(id)) return
            val kind = e.optString("kind")
            val url = e.optString("url")
            val model = e.optString("model")
            if (!llmRemoteTestAllowed(kind, url, model)) return
            llmBusy = llmBusy + id
            llmResults = llmResults - id
            workers.execute {
                val result = try {
                    probeLlmRemote(kind, url, model)
                } catch (ex: Exception) {
                    ProviderTest.mapRemoteLlmResult(
                        null, 0L, ProviderTest.SERVER_TIMEOUT_MS,
                        ex.message ?: "failed",
                    )
                }
                runOnUiThread {
                    llmBusy = llmBusy - id
                    llmResults = llmResults + (id to result)
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") version
        // Singleton rename-migrate (LLM): same pattern as STT.
        LaunchedEffect(version) {
            val arr = entriesOf(lm, "entries")
            var fixed = false
            for (i in 0 until arr.length()) {
                val en = arr.optJSONObject(i) ?: continue
                if (needsLocalRename(en.optString("kind"), en.optString("name"))) {
                    en.put("name", LOCAL_ENTRY_NAME)
                    fixed = true
                }
            }
            if (fixed) {
                save()
                version++
            }
        }
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
                    if (isLocal) {
                        // Same ONE chain-row pattern as STT
                        // ([localChainRowBlocks]): title, summary, pack
                        // status, using-model line, toggle, Why?,
                        // delete. Download / progress / delete-confirm
                        // live in the collapsible on-device section
                        // below, never in the row. Lite flavor (no
                        // runner): full-APK pointer instead of status.
                        val llmStatus = if (!llmRunner) {
                            getString(R.string.local_need_full)
                        } else {
                            when (llmSelPack) {
                                LocalPackState.PRESENT ->
                                    getString(R.string.local_pack_present)
                                LocalPackState.MISSING ->
                                    getString(R.string.local_pack_missing)
                                LocalPackState.MISMATCH ->
                                    getString(R.string.local_pack_mismatch)
                                LocalPackState.INCOMPLETE ->
                                    getString(
                                        R.string.local_downloading,
                                        llmSelId ?: "",
                                    )
                            }
                        }
                        LocalChainRow(
                            e = e,
                            summary = getString(R.string.cleanup_local_summary),
                            statusText = llmStatus,
                            statusOk = llmRunner && llmSelPack == LocalPackState.PRESENT,
                            usingModelText = getString(
                                R.string.stt_using_model,
                                llmSelId ?: "",
                            ),
                            onToggle = {
                                onLocalToggle(e, it, llmSelPack, { version++ }, llmRunner)
                            },
                            onWhy = { localInfo = true },
                            onDelete = {
                                val next = JSONArray()
                                for (j in 0 until entries.length()) {
                                    if (entries.optJSONObject(j)?.optString("id") != e.optString("id")) {
                                        next.put(entries.get(j))
                                    }
                                }
                                lm.put("entries", next); save(); version++
                            },
                            handle = handle,
                        )
                        // Local LLM self-test (existing self-test: fixed prompt
                        // + "this is a test" via generate(), never
                        // Ollama/cloud), same ONE row pattern as every
                        // provider row. Disabled unless the SELECTED pack
                        // verifies (multi-pack: one active model). With no
                        // on-device bridge the test cannot pass, so it is
                        // not offered at all — the honest reason shows
                        // instead of a button that only ever FAILs.
                        if (!llmSelfTestOffered(llmRunner)) {
                            Text(
                                stringResource(R.string.llm_selftest_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ProviderTestRow(
                                busy = llmTestBusy,
                                result = llmTestResult,
                                enabled = llmSelfTestAllowed(llmRunner, llmSelPack),
                                onTest = { startLlmSelfTest() },
                                disabledReason = localTestDisabledReason(
                                    llmRunner,
                                    llmSelPack == LocalPackState.PRESENT,
                                ),
                            )
                        }
                    } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.padding(end = 4.dp)) { handle() }
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
                            IconButton(onClick = { dialog = JSONObject(e.toString()) }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
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
                        // Remote LLM test (minimal ping, no heavy
                        // generate), same ONE pattern.
                        val llmId = e.optString("id")
                        ProviderTestRow(
                            busy = llmBusy.contains(llmId),
                            result = llmResults[llmId],
                            enabled = llmRemoteTestAllowed(
                                e.optString("kind"),
                                e.optString("url"),
                                e.optString("model"),
                            ),
                            onTest = { startLlmRemoteTest(e) },
                            disabledReason = llmTestDisabledReason(
                                e.optString("kind"),
                                e.optString("url"),
                                e.optString("model"),
                            ),
                        )
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
            // On-device model file (collapsible, collapsed by
            // default): same card-block order as the STT model cards
            // ([localModelCardBlocks]) — picker, notes, status, dest,
            // progress, actions. Moved out of the chain row so both
            // chains share the ONE row pattern above.
            val llmLocalOn = run {
                val arr = entriesOf(lm, "entries")
                var on = false
                for (i in 0 until arr.length()) {
                    val en = arr.optJSONObject(i) ?: continue
                    if (en.optString("kind") == "Local") {
                        on = en.optBoolean("enabled", true)
                        break
                    }
                }
                on
            }
            CollapsibleModelsSection(
                title = stringResource(R.string.llm_local_models_title),
                summary = localSectionSummary(
                    llmSelId ?: "",
                    llmLocalOn,
                    getString(R.string.local_state_enabled),
                    getString(R.string.local_state_disabled),
                ),
                prefKey = PREF_LLM_LOCAL_EXPANDED,
            ) {
                Text(
                    stringResource(R.string.llm_local_models_sub),
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
                // Device total RAM for the per-model warning (display only).
                val llmRamMb = remember { totalRamMb() }
                for (a in assets) {
                    val row = llmRowOf(a)
                    val isSelected = a.id == llmSelId
                    val prog = llmDlProg[a.id]
                    val noPin = isPlaceholderSha(a.sha256)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (isSelected) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ) else CardDefaults.cardColors(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(a.id, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (a.sizeBytes > 0) {
                                            "≈${a.sizeBytes / 1_000_000}MB"
                                        } else {
                                            ""
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isSelected) {
                                    OutlinedButton(onClick = {}, enabled = false) {
                                        Text(stringResource(R.string.action_using))
                                    }
                                } else {
                                    OutlinedButton(onClick = { selectLlm(a) }) {
                                        Text(stringResource(R.string.action_use))
                                    }
                                }
                            }
                            if (noPin && row != LocalModelDownload.RowState.PRESENT) {
                                Text(
                                    stringResource(R.string.llm_no_pin),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (a.minRamMb > 0) {
                                Text(
                                    stringResource(R.string.llm_ram_need, a.minRamMb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (llmRamMb > 0 && llmRamMb < a.minRamMb &&
                                    row != LocalModelDownload.RowState.PRESENT
                                ) {
                                    Text(
                                        stringResource(R.string.llm_ram_warn),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (llmRunner) {
                                Text(
                                    when (row) {
                                        LocalModelDownload.RowState.PRESENT ->
                                            stringResource(R.string.local_pack_present)
                                        LocalModelDownload.RowState.MISMATCH ->
                                            stringResource(R.string.local_pack_mismatch)
                                        LocalModelDownload.RowState.DOWNLOADING,
                                        LocalModelDownload.RowState.INCOMPLETE,
                                        -> {
                                            // DOWNLOADING = live worker progress;
                                            // INCOMPLETE = interrupted file, resume
                                            // from verified bytes on disk.
                                            val total = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                                prog?.second
                                            } else {
                                                a.sizeBytes.takeIf { it > 0 }
                                            }
                                            val received = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                                prog?.first ?: 0L
                                            } else {
                                                llmBytesOf(a.id)
                                            }
                                            if (total != null && total > 0) {
                                                stringResource(
                                                    R.string.local_downloading_mb,
                                                    a.id,
                                                    received / 1_000_000,
                                                    total / 1_000_000,
                                                )
                                            } else {
                                                stringResource(
                                                    R.string.local_downloading,
                                                    a.id,
                                                )
                                            }
                                        }
                                        LocalModelDownload.RowState.FAILED ->
                                            stringResource(
                                                R.string.local_download_failed,
                                                llmDlErr[a.id] ?: "failed",
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
                            }
                            Text(
                                stringResource(
                                    R.string.llm_pack_dest,
                                    llmTaskFileFor(filesDir, a.id).absolutePath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (row == LocalModelDownload.RowState.DOWNLOADING ||
                                row == LocalModelDownload.RowState.INCOMPLETE
                            ) {
                                Spacer(Modifier.height(4.dp))
                                val frac = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                    LocalModelDownload.progressFraction(
                                        prog?.first ?: 0L, prog?.second,
                                    )
                                } else {
                                    LocalModelDownload.progressFraction(
                                        llmBytesOf(a.id),
                                        a.sizeBytes.takeIf { it > 0 },
                                    )
                                }
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
                                if (!llmRunner) {
                                    // Lite flavor: no Download offered (could never
                                    // run) — full-APK pointer instead.
                                    Text(
                                        stringResource(R.string.local_need_full),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else when (row) {
                                    LocalModelDownload.RowState.DOWNLOADING -> {}
                                    LocalModelDownload.RowState.FAILED -> {
                                        OutlinedButton(
                                            enabled = !noPin,
                                            onClick = { startLlmDownload(a) },
                                        ) {
                                            Text(stringResource(R.string.action_retry))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { llmDeleteConfirm = a.id }) {
                                            Text(stringResource(R.string.action_delete))
                                        }
                                    }
                                    LocalModelDownload.RowState.INCOMPLETE -> {
                                        // Interrupted file: Resume re-downloads
                                        // (verified bytes are skipped).
                                        OutlinedButton(
                                            enabled = !noPin,
                                            onClick = { startLlmDownload(a) },
                                        ) {
                                            Text(stringResource(R.string.local_resume))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { llmDeleteConfirm = a.id }) {
                                            Text(stringResource(R.string.action_delete))
                                        }
                                    }
                                    LocalModelDownload.RowState.PRESENT,
                                    LocalModelDownload.RowState.MISMATCH,
                                    -> {
                                        if (row == LocalModelDownload.RowState.MISMATCH) {
                                            OutlinedButton(
                                                enabled = !noPin,
                                                onClick = { startLlmDownload(a) },
                                            ) {
                                                Text(stringResource(R.string.local_download))
                                            }
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        TextButton(onClick = { llmDeleteConfirm = a.id }) {
                                            Text(stringResource(R.string.action_delete))
                                        }
                                    }
                                    LocalModelDownload.RowState.MISSING -> {
                                        OutlinedButton(
                                            enabled = !noPin,
                                            onClick = { startLlmDownload(a) },
                                        ) {
                                            Text(stringResource(R.string.local_download))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        llmDeleteConfirm?.let { doomedId ->
            val doomed = assets.firstOrNull { it.id == doomedId }
            AlertDialog(
                onDismissRequest = { llmDeleteConfirm = null },
                title = { Text(stringResource(R.string.local_delete_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.local_delete_body,
                            doomed?.id ?: doomedId,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        llmDeleteConfirm = null
                        // Snapshot selection now (composable state, UI thread).
                        val wasSelected = doomedId == llmSelId
                        val presentAfter = assets
                            .filter { it.id != doomedId && llmPackOf(it.id) == LocalPackState.PRESENT }
                            .map { it.id }
                        workers.execute {
                            try {
                                if (doomed != null) {
                                    LocalLlmManager.deleteModel(
                                        llmTaskFileFor(filesDir, doomed.id),
                                        listOf(doomed),
                                    )
                                    // Adopted legacy slot: clear it only when
                                    // it backs the deleted asset.
                                    try {
                                        val legacy =
                                            LocalLlmManager.defaultTaskFile(filesDir)
                                        if (LocalLlmManager.verifySha256(legacy, doomed.sha256)) {
                                            legacy.delete()
                                        }
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                            runOnUiThread {
                                llmDlErr = llmDlErr - doomedId
                                llmDlBusy = llmDlBusy - doomedId
                                if (wasSelected) {
                                    // Deleting the active pack reselects
                                    // the first remaining verified asset;
                                    // with nothing left the manifest
                                    // default stays selected and the chain
                                    // moves on — never a dead selection.
                                    val next = LocalModelDownload.fallbackAfterDelete(
                                        doomedId,
                                        doomedId,
                                        presentAfter,
                                        llmDefaultId(assets),
                                    )
                                    statePrefs.edit() {
                                        putString(PREF_SELECTED_LLM, next)
                                    }
                                    if (presentAfter.contains(next)) {
                                        toast(
                                            getString(
                                                R.string.local_deleted_active,
                                                doomedId,
                                                next,
                                            ),
                                        )
                                    } else {
                                        toast(
                                            getString(
                                                R.string.local_deleted_active_none,
                                                doomedId,
                                            ),
                                        )
                                    }
                                } else {
                                    toast(getString(R.string.local_deleted))
                                }
                                version++
                            }
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { llmDeleteConfirm = null }) {
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
                            when (llmSelPack) {
                                LocalPackState.PRESENT ->
                                    stringResource(R.string.local_pack_present)
                                LocalPackState.MISSING ->
                                    stringResource(R.string.local_pack_missing)
                                LocalPackState.MISMATCH ->
                                    stringResource(R.string.local_pack_mismatch)
                                LocalPackState.INCOMPLETE ->
                                    stringResource(
                                        R.string.local_downloading,
                                        llmSelId ?: "",
                                    )
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                R.string.llm_pack_dest,
                                (llmSelected?.let { llmTaskFileFor(filesDir, it.id) }
                                    ?: LocalLlmManager.defaultTaskFile(filesDir)).absolutePath,
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
            LlmDialog(
                d,
                onClose = { dialog = null },
                onSave = { edited ->
                    upsertEntry(lm, "entries", edited)
                    save(); version++; dialog = null
                },
                hasOtherLocal = run {
                    val arr = entriesOf(lm, "entries")
                    val id = d.optString("id")
                    var found = false
                    for (i in 0 until arr.length()) {
                        val en = arr.optJSONObject(i) ?: continue
                        if (en.optString("kind") == "Local" && en.optString("id") != id) {
                            found = true
                            break
                        }
                    }
                    found
                },
            )
        }
    }

    @Composable
    private fun LlmDialog(
        e: JSONObject,
        onClose: () -> Unit,
        onSave: (JSONObject) -> Unit,
        hasOtherLocal: Boolean = false,
    ) {
        val originalKind = remember { e.optString("kind", "Ollama") }
        val isLocalEdit = remember { localEditHidesKindPicker(originalKind) }
        var name by remember { mutableStateOf(e.optString("name")) }
        var kind by remember { mutableStateOf(e.optString("kind", "Ollama")) }
        val isLocalKind = kind == "Local"
        val nameLocked = localNameLocked(kind)
        val singletonBlocked = hasOtherLocal && isLocalKind && !isLocalEdit
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
        // Draft test: same ONE ProviderTestRow pattern as the chain
        // rows (same button + latency/excerpt inline), run against the
        // unsaved draft values. Local-kind rows have no endpoint to
        // probe — the on-device test lives on the chain row instead.
        var draftBusy by remember { mutableStateOf(false) }
        var draftResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        fun startDraftTest() {
            if (isLocalKind || !llmRemoteTestAllowed(kind, url, model) || draftBusy) return
            draftBusy = true
            draftResult = null
            val k = kind
            val u = url
            val m = model
            workers.execute {
                val result = try {
                    probeLlmEndpoint(k, u, m)
                } catch (ex: Exception) {
                    ProviderTest.mapRemoteLlmResult(
                        null, 0L, ProviderTest.SERVER_TIMEOUT_MS,
                        ex.message ?: "failed",
                    )
                }
                runOnUiThread {
                    draftBusy = false
                    draftResult = result
                }
            }
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(kind) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = if (nameLocked) LOCAL_ENTRY_NAME else name,
                        onValueChange = { if (!nameLocked) name = it },
                        enabled = !nameLocked,
                        label = { Text(stringResource(R.string.stt_name)) },
                        supportingText = {
                            if (nameLocked) Text(stringResource(R.string.local_name_locked))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!isLocalEdit) {
                        DropDown(getString(R.string.llm_kind), kind, listOf("Ollama", "Local", "Cloud")) {
                            kind = it
                            if (it == "Local") name = LOCAL_ENTRY_NAME
                            draftResult = null
                        }
                    }
                    if (singletonBlocked) {
                        Text(
                            stringResource(R.string.local_singleton_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onClose) {
                            Text(stringResource(R.string.local_show_existing))
                        }
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
                        OutlinedTextField(value = url, onValueChange = { url = it; draftResult = null },
                            label = { Text(if (kind == "Cloud") stringResource(R.string.llm_url_cloud) else stringResource(R.string.llm_url)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it; draftResult = null },
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
                            stringResource(R.string.stt_apikey_note),
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
                    if (!isLocalKind) {
                        // Draft probe: same ONE row pattern (button +
                        // latency/excerpt), same gate as the saved rows.
                        ProviderTestRow(
                            busy = draftBusy,
                            result = draftResult,
                            enabled = llmRemoteTestAllowed(kind, url, model),
                            onTest = { startDraftTest() },
                            disabledReason = llmTestDisabledReason(kind, url, model),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !singletonBlocked,
                    onClick = {
                    // New Local entries always start off (default-off):
                    // the list toggle enables them only with a verified pack.
                    // Local-kind rows always persist as "Local" (rename-migrate).
                    val localKind = kind == "Local"
                    e.put("name", normalizedEntryName(kind, name)).put("kind", kind)
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
                // First-foreground promote: a boot-parked sync starts
                // here (activity visible = FGS exemption holds), then the
                // status refresh below picks it up. Fires once per tab
                // entry; promotePendingOnForeground is a no-op (false)
                // unless a boot/retry actually parked the flag.
                LaunchedEffect(Unit) {
                    val promoted = try {
                        SyncService.promotePendingOnForeground(this@MainActivity)
                    } catch (_: Exception) {
                        false
                    }
                    if (promoted) {
                        workers.execute {
                            try {
                                Thread.sleep(1_000)
                            } catch (_: InterruptedException) {
                            }
                            runOnUiThread { refresh() }
                        }
                    }
                }
                // Honest interim copy from power-on: a parked
                // boot/retry reads "waiting…", never silent nothing, so
                // the minutes-late start never looks broken.
                val pendingCopy = if (!running) {
                    BootPolicy.bootPendingCopy(
                        syncEnabled = sync.optBoolean("enabled", true),
                        startOnBoot = sync.optBoolean("start_on_boot", true),
                        pending = try {
                            BootReceiver.isSyncBootPending(this@MainActivity)
                        } catch (_: Exception) {
                            false
                        },
                        attempt = try {
                            BootReceiver.pendingAttempt(this@MainActivity)
                        } catch (_: Exception) {
                            0
                        },
                        maxRetries = BootReceiver.MAX_RETRIES,
                    )
                } else {
                    null
                }
                Text(
                    if (running) {
                        val shown = displayName.ifEmpty { status?.optString("nodeId", "")?.take(12) + "…" }
getString(R.string.sync_running, shown)
                    } else {
                        pendingCopy ?: "Sync stopped."
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
            val trusted = peers.filter { it.syncBucket() == PeerBucket.TRUSTED }
            // Incoming requests (Accept/Reject) vs our own sent requests
            // (waiting for their accept: cancel only, never Accept).
            // Mutual rows (both flags) stay incoming: Accept clears both.
            val requests = peers.filter { it.syncBucket() == PeerBucket.INCOMING }
            val waiting = peers.filter { it.syncBucket() == PeerBucket.WAITING }
            val nearby = peers.filter { it.syncBucket() == PeerBucket.NEARBY }
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
            Section("Waiting for response (${waiting.size})") {
                if (waiting.isNotEmpty()) Text(
                    "You sent a pairing request. Accept it on the other device; " +
                        "no action needed here except cancel.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (waiting.isEmpty()) Text(stringResource(R.string.sync_requests_none), style = MaterialTheme.typography.bodySmall)
                waiting.forEach { p ->
                    val id = p.optString("nodeId")
                    val nm = p.optString("name").ifEmpty { id.take(12) + "…" }
                    PeerRow(
                        p,
                        // No onAccept: our own request is never acceptable
                        // locally. Cancel withdraws without revoking, so
                        // the id stays pairable.
                        note = notes[id] ?: getString(R.string.sync_waiting, nm),
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
                                // A mutual round means both sides already
                                // consented: promote to trusted (nearby flow).
                                if (msg.startsWith("synced")) {
                                    try {
                                        CoreBridge.syncTrust(id.trim().lowercase(), true).getOrThrow()
                                    } catch (_: Exception) {
                                    }
                                }
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

    /** Bucket a sync-tab peer row: the UI split behind `peerBucket`. */
    private fun JSONObject.syncBucket(): PeerBucket = peerBucket(
        trusted = optBoolean("trusted"),
        pending = optBoolean("pending"),
        outgoing = optBoolean("outgoing"),
        discovered = optBoolean("discovered"),
    )

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

/** Sync-tab bucket for a peer row. INCOMING rows get Accept/Reject;
 *  WAITING rows (requests we sent) get cancel only, never Accept. */
internal enum class PeerBucket { TRUSTED, INCOMING, WAITING, NEARBY, OTHER }

/**
 * Pure split behind the sync tab (unit-tested): `pending` stays
 * incoming-only (our sent requests never set it), mutual rows (both
 * flags) stay incoming so one Accept clears both, trusted wins over
 * everything, undiscovered strangers stay out of Nearby.
 */
internal fun peerBucket(
    trusted: Boolean,
    pending: Boolean,
    outgoing: Boolean,
    discovered: Boolean,
): PeerBucket = when {
    trusted -> PeerBucket.TRUSTED
    pending -> PeerBucket.INCOMING
    outgoing -> PeerBucket.WAITING
    discovered -> PeerBucket.NEARBY
    else -> PeerBucket.OTHER
}
