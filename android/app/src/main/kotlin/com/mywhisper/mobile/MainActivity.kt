package com.mywhisper.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.key
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Dictation owns floating-button setup and preferences; Voice groups
 * recognition and cleanup; Library owns words, history, and device sync.
 * Settings round-trip through the core JSON shared with the desktop app.
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
    private val notificationsAllowed = mutableStateOf(false)
    private val a11yOn = mutableStateOf(false)
    private val micRationale = mutableStateOf(false)
    private val micBlocked = mutableStateOf(false)
    private var micDeniedBefore = false
    private val workers = Executors.newSingleThreadExecutor()
    private val sttVerifyCache = LocalModelDownload.PackVerifyCache()
    private val llmVerifyCache = LocalModelDownload.PackVerifyCache()
    private val sttPackChanges = mutableIntStateOf(0)
    private val llmPackChanges = mutableIntStateOf(0)
    /** Active background-download WorkInfo observers, keyed by unique work name. */
    private val dlWorkObservers = mutableMapOf<String, Observer<List<WorkInfo>>>()

    /**
     * Download-notification deep-link target: tapping a download
     * notification opens [Root] directly on the right page (STT vs
     * LLM models) via the [EXTRA_DL_TARGET] intent extra — cold start
     * (onCreate) and warm tap (onNewIntent, singleTop) alike.
     */
    private val deepLinkTab = mutableStateOf<Int?>(null)
    /** Activity nav item tapped while Activity is open: scroll back to the top. */
    private val activityScrollTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Bumped on resume and focus regain; Activity reloads stats and history on change. */
    private val resumeTick = mutableIntStateOf(0)

    /** On-device pack state for the STT/LLM Local rows. Pure for unit tests. */
    internal enum class LocalPackState { PRESENT, MISSING, MISMATCH, INCOMPLETE }

    /**
     * Runner-gated row state for the Local STT/LLM download rows.
     * NEED_FULL_FLAVOR = no on-device runtime for this device/build
     * (not arm64, or no pack URL): an explanation shows instead of
     * Download; the rest mirror the
     * verified-pack row machine ([LocalModelDownload.RowState]).
     * Pure for unit tests.
     */
    internal enum class GatedRowState {
        NEED_FULL_FLAVOR, MISSING, DOWNLOADING, PRESENT, MISMATCH, FAILED
    }

    companion object {
        /** Appearance cached in `mw_state` so a cold start paints the right theme before settings load. */
        internal const val PREF_THEME = "ui_theme"
        private const val PREF_BLACK = "ui_black_background"

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
         * vendor default, which requires an API key). Pure for unit tests.
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
                if (cloudCredentialsMissing(e.optString("kind"), e.optString("url"), e.optString("api_key"))) continue
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
         * Per-asset model file: `models/llm-<assetId>.gguf`, so several
         * downloaded LLM packs coexist (one file per asset) while the
         * legacy single `cleanup.gguf` slot stays untouched. Pure for
         * unit tests (file mapping only, no Context).
         */
        @JvmStatic
        internal fun llmTaskFileFor(dir: File, assetId: String, ext: String = LocalLlmManager.MODEL_EXT): File {
            val safe = assetId.map { c ->
                if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
            }.joinToString("").ifBlank { "model" }
            val cleanExt = ext.trimStart('.').ifBlank { LocalLlmManager.MODEL_EXT }
            return File(File(dir, "models"), "llm-$safe.$cleanExt")
        }

        /** Asset-aware slot: extension follows the entry runtime. */
        @JvmStatic
        internal fun llmTaskFileFor(dir: File, asset: LocalLlmManager.ModelAsset): File =
            llmTaskFileFor(dir, asset.id, LocalLlmManager.MODEL_EXT)

        /**
         * Files whose stats gate an LLM asset's cached verdict: the
         * slot, the legacy slot, and this asset's own `.part` files.
         * Stat-only (no content reads) — safe anywhere, including
         * composition. Pure java.io for unit tests.
         */
        @JvmStatic
        internal fun llmPackFiles(dir: File, asset: LocalLlmManager.ModelAsset): List<File> {
            val slot = llmTaskFileFor(dir, asset)
            val modelsDir = slot.parentFile ?: dir
            val parts = try {
                modelsDir.listFiles { f ->
                    ModelDownloadSchedule.isOwnLlmPart(f.name, slot.name, asset.id)
                }?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val legacy = try {
                LocalLlmManager.defaultTaskFile(dir).takeIf { it != slot }
            } catch (_: Exception) {
                null
            }
            return listOf(slot) + parts + listOfNotNull(legacy)
        }

        /**
         * Files whose stats gate an STT model's cached verdict: the
         * pack files plus this model's `.part` files. Stat-only, pure
         * java.io for unit tests.
         */
        @JvmStatic
        internal fun sttPackFiles(dir: File, entry: LocalSttModels.Entry): List<File> {
            val modelDir = LocalSttModels.dirFor(dir, entry)
            val pack = entry.files.map { f -> File(modelDir, f.name) }
            val parts = try {
                modelDir.listFiles { f -> f.name.endsWith(".part") }?.toList()
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            return pack + parts
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
            val slot = llmTaskFileFor(dir, asset)
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
            // Direct-call lambda, NOT a ::verifySha256 function
            // reference: callable references need Kotlin metadata at
            // runtime, which R8 mangles (release showed every pack as
            // missing). A lambda compiles to a plain static call.
            llmInspectWithVerifier(dir, asset, { f, s -> LocalLlmManager.verifySha256(f, s) })

        /**
         * True when any trace of [asset] lingers on disk: the slot, the
         * legacy slot, or a stale per-asset `.part` from an interrupted
         * download. Pure for unit tests (java.io only).
         */
        @JvmStatic
        internal fun hasLlmTrace(dir: File, asset: LocalLlmManager.ModelAsset): Boolean {
            val slot = llmTaskFileFor(dir, asset)
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
            val slot = llmTaskFileFor(dir, asset)
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

        /**
         * STT Local test-button reason visibility: hidden without a
         * runner (lite) — the row card's own status line directly
         * above already shows the full-APK pointer, and a second copy
         * doubles the paragraph (owner photo). Runner builds always
         * show the explicit reason per the ONE row pattern. Pure for
         * unit tests.
         */
        @JvmStatic
        internal fun sttLocalTestShowsReason(runnerSupported: Boolean): Boolean =
            runnerSupported

        /** Server STT row test goes live when the config validates. Pure. */
        @JvmStatic
        internal fun sttServerTestAllowed(kind: String, url: String, model: String): Boolean =
            ProviderTest.sttServerTestAllowed(kind, url, model)

        /** Remote LLM row test goes live when the config validates. Pure. */
        @JvmStatic
        internal fun llmRemoteTestAllowed(kind: String, url: String, model: String): Boolean =
            ProviderTest.llmRemoteTestAllowed(kind, url, model)

        /**
         * Runtime gate for the Local STT/LLM rows: without an on-device
         * runtime for this device/build ([LocalAiRuntime.supported]),
         * downloads could never run. False forces the NEED_FULL_FLAVOR
         * row, which explains why instead of offering Download. True
         * defers to the verified-pack row machine.
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
                LocalModelDownload.RowState.CHECKING,
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

        /** On-device STT runner available (runtime pack for this ABI). Never throws. */
        @JvmStatic
        internal fun sttRunnerSupported(): Boolean = LocalAiRuntime.supported()

        /** On-device LLM runner available (same runtime pack as STT). Never throws. */
        @JvmStatic
        internal fun llmRunnerSupported(): Boolean = LocalAiRuntime.supported()

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
  "provider": "llama.cpp",
  "notes": "Binaries NEVER committed. Download via the in-app Text cleanup > Local row (SHA-256 verified) or scripts/fetch-local-llm.sh. GGUF files run on llama.cpp from the on-device AI runtime pack (GPU first). SHAs are the Hugging Face LFS oids. All files are ungated.",
  "models": [
    {
      "id": "gemma3-1b-it-q4_0",
      "url": "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_0.gguf",
      "sha256": "27ee88e03be02e9ba73def9a819d570d8ad73716e50769e87f374ae394b0276e",
      "size_bytes": 721918496,
      "min_ram_mb": 3072,
      "note": "Small-phone option: ~0.7GB, fits 4-6GB phones."
    },
    {
      "id": "gemma4-e2b-it-qat-q4_0",
      "url": "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf",
      "sha256": "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634",
      "size_bytes": 3349516256,
      "min_ram_mb": 6144,
      "thinking": true,
      "note": "Gemma 4 E2B, Google's quantization-aware Q4_0 (Apache-2.0). 8GB+ phones."
    },
    {
      "id": "gemma4-e4b-it-qat-q4_0",
      "url": "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B_q4_0-it.gguf",
      "sha256": "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
      "size_bytes": 5154941280,
      "min_ram_mb": 10240,
      "thinking": true,
      "note": "Gemma 4 E4B, Google's quantization-aware Q4_0 (Apache-2.0). Heaviest option: 12GB phones."
    }
  ]
}"""
    }

    /** Speech language codes (stored code, shown label in the app's language). */
    private val langs: List<Pair<String, String>>
        get() {
            val appLocale = java.util.Locale.forLanguageTag(getString(R.string.lang_names_locale))
            return listOf("auto" to getString(R.string.lang_auto)) + listOf(
                "en", "es", "de", "fr", "it", "pt", "nl", "pl", "ru", "uk", "tr", "ar", "hi", "zh",
                "ja", "ko", "ca", "gl", "eu", "sv", "da", "fi", "no", "cs", "ro", "hu", "el",
            ).map { code ->
                code to java.util.Locale.forLanguageTag(code).getDisplayLanguage(appLocale)
                    .replaceFirstChar { it.titlecase(appLocale) }
            }
        }

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
        refreshPermStates()
        if (!granted) toast(getString(R.string.setup_notif_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fold recreates this activity: record the panel so foldables get separate screen settings.
        FoldScreens.observe(this)
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
        paintCachedWindowBackground()
        setContent { Root() }
    }

    /** The window shows before Compose draws: give it the cached theme's background, not the manifest default. */
    private fun paintCachedWindowBackground() {
        val prefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        val dark = when (prefs.getString(PREF_THEME, "System")) {
            "Light" -> false
            "Dark" -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val color = when {
            dark && prefs.getBoolean(PREF_BLACK, false) -> Color.Black
            dark -> Color(0xFF121416)
            else -> WhisperLightColors.background
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color.toArgb()))
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
        // Back from Settings (by hand or auto-return): stop the poll and close the guide.
        SettingsGuide.stop()
        refreshPermStates()
        reload()
        resumeTick.intValue++
    }

    /** Multi-window / overlays: regaining focus also refreshes Activity. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) resumeTick.intValue++
    }

    override fun onDestroy() {
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
        notificationsAllowed.value = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
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

    private fun requestNotificationPermission() {
        refreshPermStates()
        if (notificationsAllowed.value) return
        val prefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notification_requested", false)) {
            prefs.edit { putBoolean("notification_requested", true) }
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /**
     * Opens the system settings page for [step] and comes back on its own once
     * the change is detected ([SettingsGuide.watch]). Accessibility and restricted
     * settings get a picture-in-picture guide when the device allows it.
     */
    private fun openSettingsAndReturn(step: SettingsGuide.Step) {
        if (step == SettingsGuide.Step.STATUS_CHANNEL) AppNotificationChannels.ensure(this)
        // Settings opens in this task, so Back and auto-return behave as without the guide.
        try {
            startActivity(SettingsGuide.intent(this, step))
        } catch (_: android.content.ActivityNotFoundException) {
            return // Locked-down or OEM Settings without this page: the step's hint line still applies.
        }
        SettingsGuide.watch(this) { SettingsGuide.granted(this, step) }
        // The guide opens on top in its own task and shrinks into a corner over Settings.
        if (SettingsGuide.usesPip(step) && SettingsGuide.pipAllowed(this)) SetupGuideActivity.start(this, step)
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
                    if (committed.optJSONObject("preferences")?.optBoolean("float_enabled", true) == true) {
                        try { OverlayService.start(this@MainActivity) } catch (_: Exception) { }
                    }
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

    private fun reconcileFloatingService(committed: JSONObject) {
        try {
            if (committed.optJSONObject("preferences")?.optBoolean("float_enabled", true) == true) {
                OverlayService.start(this)
            } else {
                OverlayService.stop(this)
            }
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.task_service_failed))
        }
    }

    private fun save(onCommitted: () -> Unit = {}) {
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
                    // Every winning save reconciles the persisted state, even if a
                    // later settings edit superseded the original enable callback.
                    reconcileFloatingService(committed)
                    onCommitted()
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
        onActive: () -> Unit = {},
        resume: Boolean = false,
    ) {
        val workName = ModelDownloadSchedule.workNameFor(kind, id)
        try {
            val live = WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(workName)
            // A screen re-entry brings fresh row state: the old observer
            // would keep writing into the discarded one.
            dlWorkObservers.remove(workName)?.let { live.removeObserver(it) }
            var seenActive = false
            val obs = Observer<List<WorkInfo>> { infos ->
                val info = infos.firstOrNull() ?: return@Observer
                val action = ModelDownloadSchedule.actionForWorkState(info.state.name)
                // Re-attaching on screen entry: only to work still running;
                // finished/failed work from earlier stays out of the row.
                if (resume && !seenActive && action != ModelDownloadSchedule.WorkRowAction.TRACK) {
                    dlWorkObservers.remove(workName)?.let { live.removeObserver(it) }
                    return@Observer
                }
                when (action) {
                    ModelDownloadSchedule.WorkRowAction.TRACK -> {
                        seenActive = true
                        onActive()
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

    /**
     * Current network posture for the download waiting row (null =
     * unknown — the row never shows waiting text then, only the
     * normal starting bar). Fully-qualified framework types: no new
     * imports for one call site.
     */
    private fun activeNetState(): LocalModelDownload.NetState? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return LocalModelDownload.NetState.OFFLINE
            val caps = cm.getNetworkCapabilities(net) ?: return null
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                LocalModelDownload.NetState.UNMETERED
            } else {
                LocalModelDownload.NetState.METERED
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun keepLabel(secs: Long): String =
        when (secs) {
            LocalKeepWarm.IMMEDIATE -> getString(R.string.keep_immediate)
            60L -> getString(R.string.keep_1min)
            600L -> getString(R.string.keep_10min)
            1800L -> getString(R.string.keep_30min)
            LocalKeepWarm.INDEFINITE -> getString(R.string.keep_indefinite)
            else -> resources.getQuantityString(R.plurals.keep_custom, secs.toInt(), secs)
        }

    private fun thinkLabel(budget: Int): String =
        if (budget <= 0) {
            getString(R.string.think_off)
        } else {
            getString(R.string.think_budget, budget)
        }

    /** Keep-warm preset menu (current value + one pick callback). */
    @Composable
    private fun KeepDropdown(current: Long, onPick: (Long) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(keepLabel(current))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (preset in LocalKeepWarm.presets()) {
                    DropdownMenuItem(
                        text = { Text(keepLabel(preset)) },
                        onClick = {
                            open = false
                            onPick(preset)
                        },
                    )
                }
            }
        }
    }

    /** Thinking menu, models with a thinking mode only (0 = off, default). */
    @Composable
    private fun ThinkDropdown(current: Int, onPick: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(thinkLabel(current))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (budget in LocalKeepWarm.thinkBudgets()) {
                    DropdownMenuItem(
                        text = { Text(thinkLabel(budget)) },
                        onClick = {
                            open = false
                            onPick(budget)
                        },
                    )
                }
            }
        }
    }

    private fun copyText(text: String, confirm: String = "Copied") {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MyWhisper", text))
            // Android 13+ already shows its own clipboard confirmation.
            if (android.os.Build.VERSION.SDK_INT < 33) toast(confirm)
        } catch (_: Exception) {
        }
    }

    /**
     * On-device diagnostics for support without USB/logcat: app
     * version, last process exits (Android 11+ records native
     * crashes with their tombstone text), free storage and RAM.
     * Never throws; empty sections degrade to one-liners.
     */
    private fun collectDiagnostics(): String {
        val sb = StringBuilder()
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                pi.versionCode.toLong()
            }
            sb.append("app ").append(pi.versionName).append(" (").append(code).append(")\n")
        } catch (_: Exception) {
            sb.append("app unknown\n")
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val exits = am.getHistoricalProcessExitReasons(packageName, 0, 3)
                if (exits.isEmpty()) {
                    sb.append("exits: none recorded\n")
                }
                for (e in exits) {
                    val ts = try {
                        java.text.SimpleDateFormat(
                            "MM-dd HH:mm:ss",
                            java.util.Locale.US,
                        ).format(java.util.Date(e.timestamp))
                    } catch (_: Exception) {
                        e.timestamp.toString()
                    }
                    sb.append("exit reason=").append(exitReasonName(e.reason))
                        .append(" ts=").append(ts)
                        .append(" pss=").append(e.pss / 1024).append("MB\n")
                    val desc = e.description?.take(700)
                    if (!desc.isNullOrBlank()) {
                        sb.append(desc).append("\n")
                    }
                }
            } catch (ex: Exception) {
                sb.append("exits: unavailable (").append(ex.message).append(")\n")
            }
        } else {
            sb.append("exits: needs Android 11+\n")
        }
        try {
            sb.append("storage free=").append(filesDir.usableSpace / 1_000_000)
                .append("MB total=").append(filesDir.totalSpace / 1_000_000).append("MB\n")
        } catch (_: Exception) {
        }
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("ram avail=").append(mi.availMem / 1_000_000)
                .append("MB total=").append(mi.totalMem / 1_000_000)
                .append("MB low=").append(mi.lowMemory).append('\n')
        } catch (_: Exception) {
        }
        // Own-process log tail (no permission needed for our own
        // UID): step logs pin a native crash to engine init /
        // conversation / sendMessage when the tombstone is bare.
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "brief", "MwLlm:I", "MwLocalAi:I", "*:S"),
            )
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val lines = out.lines().filter { it.isNotBlank() }.takeLast(40)
            sb.append("--- logtail (").append(lines.size).append(") ---\n")
            for (line in lines) {
                sb.append(line.take(300)).append('\n')
            }
        } catch (_: Exception) {
            sb.append("logtail: unavailable\n")
        }
        return sb.toString()
    }

    private fun exitReasonName(r: Int): String =
        when (r) {
            0 -> "UNKNOWN"
            1 -> "EXIT_SELF"
            2 -> "SIGNALED"
            3 -> "LOW_MEMORY"
            4 -> "CRASH_NATIVE"
            5 -> "CRASH"
            6 -> "SHUTDOWN"
            7 -> "ANR"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "INITIALIZATION_FAILURE"
            11 -> "DEPENDENCY_DIED"
            12 -> "STATE_CHANGE"
            13 -> "USER_REQUESTED"
            14 -> "USER_STOPPED"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            17 -> "OTHER"
            else -> "REASON_$r"
        }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val monitoredLlmAssets = remember { llmAssets() }
        rememberPackChanges(
            filesDir,
            LocalSttModels.MODELS.map { LocalSttModels.dirFor(filesDir, it) } + File(filesDir, "models"),
            onChange = { changed ->
                val sttIds = LocalSttModels.MODELS.filter { model ->
                    affectsPackPath(changed, LocalSttModels.dirFor(filesDir, model))
                }.map { it.id }
                val llmIds = monitoredLlmAssets.filter { asset ->
                    val slot = llmTaskFileFor(filesDir, asset)
                    val legacy = LocalLlmManager.defaultTaskFile(filesDir)
                    affectsPackPath(changed, slot) || affectsPackPath(changed, legacy) ||
                        (changed.parentFile == slot.parentFile &&
                            ModelDownloadSchedule.isOwnLlmPart(changed.name, slot.name, asset.id))
                }.map { it.id }
                sttIds.forEach { sttVerifyCache.invalidate(it) }
                llmIds.forEach { llmVerifyCache.invalidate(it) }
                if (sttIds.isNotEmpty()) sttPackChanges.intValue++
                if (llmIds.isNotEmpty()) llmPackChanges.intValue++
            },
        )
        val s = settingsState.value
        // Until settings load, use the appearance cached from the last run: no wrong-theme first frame.
        val themePrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        val prefs = s?.optJSONObject("preferences")
        val theme = if (s != null) prefs?.optString("theme", "System") ?: "System" else themePrefs.getString(PREF_THEME, "System")!!
        val black = if (s != null) prefs?.optBoolean("black_background", false) == true else themePrefs.getBoolean(PREF_BLACK, false)
        if (s != null) LaunchedEffect(theme, black) { themePrefs.edit { putString(PREF_THEME, theme); putBoolean(PREF_BLACK, black) } }
        val dark = when (theme) {
            "Light" -> false
            "Dark" -> true
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        val colors = animatedColorScheme(whisperColorScheme(dark, black = black))
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            // Android 15+ draws these bars transparently over the app.
            // Older versions need matching colors as well as matching icons.
            if (android.os.Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colors.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colors.background.toArgb()
            }
        }
        MaterialTheme(colorScheme = colors) {
            val uiPrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
            var backStack by rememberSaveable { mutableStateOf(intArrayOf()) }
            var tab by rememberSaveable {
                mutableIntStateOf(deepLinkTab.value ?: if (uiPrefs.getBoolean("bubble_onboarding_seen", false)) 0 else 16)
            }
            fun navigate(target: Int) { backStack = backStack + tab; tab = target }
            fun finishOnboarding() {
                uiPrefs.edit { putBoolean("bubble_onboarding_seen", true) }
                backStack = intArrayOf(); tab = 0
            }
            fun goBack() {
                if (backStack.isNotEmpty()) {
                    tab = backStack.last(); backStack = backStack.dropLast(1).toIntArray()
                }
            }
            // Notification deep-link: a warm tap re-routes the open app.
            // Consumed after routing (set null): without this a re-tap
            // on the same target is a no-op key change and gets ignored.
            LaunchedEffect(deepLinkTab.value) {
                val pending = deepLinkTab.value
                if (pending != null) {
                    backStack = intArrayOf()
                    tab = deepLinkTargetTab(pending, tab)
                    deepLinkTab.value = null
                }
            }
            val snackbarHost = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                snackbarEvents.collect { snackbarHost.showSnackbar(it) }
            }
            val titles = mapOf(
                0 to getString(R.string.tab_activity), 1 to getString(R.string.nav_providers),
                2 to getString(R.string.nav_providers), 3 to getString(R.string.tab_dict),
                6 to getString(R.string.ux_devices_sync),
                9 to getString(R.string.setup_float),
                8 to getString(R.string.new_notifications), 10 to getString(R.string.setup_mic_button),
                11 to getString(R.string.new_appearance),
                13 to getString(R.string.diag_title),
                16 to getString(R.string.task_welcome), 17 to getString(R.string.nav_settings),
                18 to getString(R.string.task_resume_setup),
            )
            val pageState = rememberSaveableStateHolder()
            // Roots: Activity (0), Providers (1 speech / 2 cleanup), Dictionary (3), Settings (17).
            val isRoot = tab in listOf(0, 1, 2, 3, 17)
            val selectedTask = when (tab) { 0 -> 0; 1, 2 -> 1; 3 -> 3; else -> 17 }
            // Providers remembers its last inner tab (speech / cleanup).
            var providersTab by rememberSaveable { mutableIntStateOf(1) }
            LaunchedEffect(tab) { if (tab == 1 || tab == 2) providersTab = tab }
            BackHandler(enabled = backStack.isNotEmpty()) { goBack() }
            // Rail on wide windows (tablets) and short ones (phones in landscape);
            // bottom bar otherwise. Same destinations, same rule for when it shows.
            val window = androidx.compose.ui.platform.LocalConfiguration.current
            val useRail = useNavigationRail(window.screenWidthDp, window.screenHeightDp)
            val showNav = isRoot && backStack.isEmpty()
            val navLabels = listOf(getString(R.string.tab_activity), getString(R.string.nav_providers),
                getString(R.string.tab_dict), getString(R.string.nav_settings))
            val navTargets = listOf(0, 1, 3, 17)
            val navIcons = listOf(R.drawable.ic_nav_activity, R.drawable.ic_voice, R.drawable.ic_library, R.drawable.ic_nav_settings)
            fun openRoot(index: Int) {
                if (navTargets[index] == 0 && tab == 0) activityScrollTop.tryEmit(Unit)
                tab = if (navTargets[index] == 1) providersTab else navTargets[index]
            }
            // The rail runs the full height beside the Scaffold (header included);
            // the Scaffold skips the start insets the rail already covers.
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (showNav && useRail) androidx.compose.material3.NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        windowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
                            androidx.compose.foundation.layout.WindowInsetsSides.Start + androidx.compose.foundation.layout.WindowInsetsSides.Vertical),
                    ) {
                        Spacer(Modifier.weight(1f))
                        navLabels.forEachIndexed { index, label ->
                            androidx.compose.material3.NavigationRailItem(
                                selected = selectedTask == navTargets[index],
                                onClick = { openRoot(index) },
                                icon = { Icon(painterResource(navIcons[index]), contentDescription = null, modifier = Modifier.size(24.dp)) },
                                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                label = { Text(label) },
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
            Scaffold(
                modifier = Modifier.weight(1f).then(
                    if (showNav && useRail) Modifier.consumeWindowInsets(
                        androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Start))
                    else Modifier),
                // Landscape: keep content and the title clear of camera cutouts too.
                contentWindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
                    .union(androidx.compose.foundation.layout.WindowInsets.displayCutout),
                topBar = {
                    TopAppBar(
                        windowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
                            androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Top),
                        title = { Text(titles[tab] ?: getString(R.string.setup_float)) },
                        navigationIcon = {
                            if (backStack.isNotEmpty()) {
                                IconButton(onClick = { goBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.new_back))
                                }
                            }
                        },
                        // Transparent: the bar's own colour animates, so on a theme switch it lagged
                        // behind the rest; the Scaffold background behind it changes instantly.
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    if (showNav && !useRail) NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        navLabels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTask == navTargets[index],
                                onClick = { openRoot(index) },
                                icon = { Icon(painterResource(navIcons[index]), contentDescription = null, modifier = Modifier.size(24.dp)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { pad ->
                Row(Modifier.fillMaxSize().padding(pad)) {
                // Pages keep a readable line length on wide windows; Activity lays out its own panes.
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = when (tab) {
                            0 -> ACTIVITY_MAX_WIDTH
                            // Swipeable pages: full width so pages slide edge to edge; each centers itself.
                            1, 2, 3, 16 -> androidx.compose.ui.unit.Dp.Infinity
                            else -> PAGE_MAX_WIDTH
                        })
                        .fillMaxSize(),
                ) {
                    if (s == null && settingsError.value.isEmpty()) {
                        LoadingBox()
                    } else if (s == null) {
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
                        pageState.SaveableStateProvider(if (tab == 2) 1 else tab) {
                        when (tab) {
                            0 -> ActivityHome(s, onNavigate = ::navigate, onOpenProviders = { tab = it })
                            9 -> BubbleScreen(s, onBehavior = { navigate(10) })
                            16 -> OnboardingScreen(s, onFinish = { finishOnboarding(); navigate(9) })
                            18 -> SetupScreen(s)
                            1, 2 -> ProvidersScreen(s, tab) { tab = it }
                            3 -> DictionaryScreen(s, onSync = { navigate(6) })
                            17 -> SettingsHome(onNavigate = ::navigate)
                            6 -> SyncScreen(s)
                            8 -> NotificationSettings()
                            10 -> MicrophoneSettings(s)
                            11 -> AppearanceSettings(s)
                            13 -> DiagnosticsSettings()

                        }
                        }
                    }
                }
                }
                }
            }
            }
        }
    }

    // -- shared widgets -------------------------------------------------

    @Composable
    private fun Section(title: String, body: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(modifier = Modifier.padding(16.dp)) {
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
        // Same card as [Section] so collapsible and fixed sections line up.
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(modifier = Modifier.animateContentSize()) {
                ExpandHeader(title, summary, expanded, { toggle() })
                if (expanded) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { body() }
                }
            }
        }
    }

    /** Provider identity edits; enable and overflow are independent 48dp targets. */
    @Composable
    private fun ProviderEntryCard(
        entry: JSONObject,
        summary: String,
        onEdit: () -> Unit,
        onToggle: (Boolean) -> Unit,
        onDelete: () -> Unit,
        handle: @Composable () -> Unit,
        busy: Boolean,
        result: ProviderTest.Result?,
        testEnabled: Boolean,
        onTest: () -> Unit,
        disabledReason: String? = null,
        status: String? = null,
        usingModel: String? = null,
        toggleEnabled: Boolean = true,
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        val name = entry.optString("name").ifBlank { entry.optString("kind") }
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(48.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) { handle() }
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onEdit).heightIn(min = 64.dp)
                    .padding(horizontal = 8.dp, vertical = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FeedbackSwitch(checked = entry.optBoolean("enabled", true), onCheckedChange = onToggle,
                    enabled = toggleEnabled,
                    modifier = Modifier.semantics { contentDescription = name })
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ux_provider_actions, name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (entry.optString("kind") == "Local") R.string.stt_local_why else R.string.action_edit)) }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.provider_test_run)) }, enabled = testEnabled && !busy,
                            onClick = { menuOpen = false; onTest() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            if (status != null || usingModel != null || busy || result != null || disabledReason != null) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    usingModel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ProviderTestRow(busy, result, testEnabled, onTest, disabledReason, showButton = false)
                }
            }
        }
    }

    /**
     * Shared test feedback for provider cards and editor forms.
     * Cards launch tests from overflow; forms show an explicit button.
     * Disabled reasons remain visible even after a stale test result.
     */
    @Composable
    private fun ProviderTestRow(
        busy: Boolean,
        result: ProviderTest.Result?,
        enabled: Boolean,
        onTest: () -> Unit,
        disabledReason: String? = null,
        showButton: Boolean = true,
    ) {
        // Result visibility: a probe finishes seconds later, and on a long
        // settings screen the inline PASS/FAIL can land off-screen (owner
        // repro: the row scrolled away and the result needed a manual
        // scroll). Bring the result itself into view when it arrives, so
        // the outcome is always visible where the button was tapped.
        val resultRequester = remember { BringIntoViewRequester() }
        LaunchedEffect(result) {
            if (result != null) {
                try {
                    resultRequester.bringIntoView()
                } catch (_: Exception) {
                }
            }
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ONE shared test-button style (min height + padding
                // identical to the Add buttons): every chain row,
                // dialog, and self-test renders through here.
                if (showButton) {
                    ProviderTestButton(enabled = enabled, busy = busy, onTest = onTest)
                    Spacer(Modifier.width(8.dp))
                }
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
                        modifier = Modifier.bringIntoViewRequester(resultRequester),
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
     * Lightweight endpoint check (no heavy transcribe/generate): a
     * gated 401/403 reads as FAIL, never PASS (see
     * [ProviderTest.probeHttpExcerpt]); other statuses read as
     * reachable (healthy POST-only routes 404/405 on GET). Only
     * network throws read as FAIL beyond that. Short timeouts.
     * Never touches engines/runners/services.
     */
    private fun httpGetCode(urlStr: String, timeoutMs: Int = 8000, apiKey: String = ""): Int {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            ProviderTest.bearerToken(apiKey)?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        try {
            conn.connect()
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** GET body snippet for model-list checks (Ollama `/api/tags`). */
    private fun httpGetBody(urlStr: String, timeoutMs: Int = 8000, maxChars: Int = 4096, apiKey: String = ""): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            ProviderTest.bearerToken(apiKey)?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        try {
            val code = conn.responseCode
            // Genuine GET endpoint (model list): 2xx required — unlike
            // the httpGetCode probes, where POST-only routes 404/405.
            ProviderTest.requireHttp2xx(code)
            return conn.inputStream.bufferedReader().use { it.readText().take(maxChars) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * THE server-probe implementations shared by chain rows and
     * add/edit dialogs (one pattern, one result shape): lightweight
     * endpoint checks, never a heavy transcribe/generate. STT/Cloud:
     * gated 401/403 = FAIL (wrong or missing key), never PASS; other
     * statuses = reachable (POST-only routes 404/405 on GET — a 404
     * with the key still proves the gate let us through). `ws(s)` /
     * blank vendor-default URL has no HTTP surface so config
     * validation stands in. LLM: blank URL = config validation;
     * Ollama checks model reachability via GET /api/tags where cheap
     * (2xx required, model must be listed). Never touches
     * engines/runners/services.
     */
    private fun probeSttEndpoint(url: String, model: String, apiKey: String = ""): ProviderTest.Result {
        val u = url.trim()
        val m = model.trim()
        if (u.isEmpty() || u.startsWith("ws", ignoreCase = true)) {
            return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
                "config ok · $m"
            }
        }
        return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
            val code = httpGetCode(u, apiKey = apiKey)
            ProviderTest.probeHttpExcerpt(code, m)
        }
    }

    private fun probeLlmEndpoint(kind: String, url: String, model: String, apiKey: String = ""): ProviderTest.Result {
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
                val body = httpGetBody("$base/api/tags", apiKey = apiKey)
                if (!body.contains(m, ignoreCase = true)) {
                    throw IllegalStateException("reachable · model not listed")
                }
                "model ok · $m"
            }
        }
        return ProviderTest.runProbe(ProviderTest.SERVER_TIMEOUT_MS) {
            val code = httpGetCode(u, apiKey = apiKey)
            ProviderTest.probeHttpExcerpt(code, m)
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
            ProviderTest.LocalBlocked.NEED_RUNNER -> getString(localUnavailableRes())
            ProviderTest.LocalBlocked.NEED_PACK -> getString(R.string.provider_test_need_pack)
        }

    @Composable
    private fun DropDown(
        label: String,
        value: String,
        options: List<String>,
        onPick: (String) -> Unit,
    ) {
        SearchableChoiceSetting(label, value, options, onPick)
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
        // pointerInput(id) outlives recompositions; read the latest list and
        // callback, or moves land on a JSON array a save() already replaced.
        val currentItems by androidx.compose.runtime.rememberUpdatedState(items)
        val currentOnMove by androidx.compose.runtime.rememberUpdatedState(onMove)
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
                            modifier = Modifier.size(48.dp).pointerInput(id) {
                                detectDragGestures(
                                    onDragStart = {
                                        if (dragging != null) return@detectDragGestures
                                        dragging = id
                                        dragIdx = currentItems.indexOfFirst { idOf(it) == id }
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
                                        // Layout still shows the pre-move order: hit-testing it
                                        // would swap straight back (the flip-flop bug).
                                        if (info.index != dragIdx) return@detectDragGestures
                                        val center = info.offset + info.size / 2f + accumulated
                                        val target = visible.firstOrNull { v ->
                                            center >= v.offset && center < v.offset + v.size
                                        }?.index ?: dragIdx
                                        if (target in currentItems.indices && target != dragIdx) {
                                            currentOnMove(dragIdx, target)
                                            dragIdx = target
                                            accumulated = 0f
                                        }
                                    },
                                )
                            }.padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    // -- Setup ----------------------------------------------------------

    /** Floating button quick toggle; the card body opens its settings. */
    @Composable
    private fun FloatingButtonQuick(s: JSONObject, setup: BubbleSetupState, onNavigate: (Int) -> Unit, onOpenProviders: (Int) -> Unit) {
        val enabled = s.optJSONObject("preferences")?.optBoolean("float_enabled", true) ?: true
        val prefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        var snoozedUntil by remember { mutableStateOf(prefs.getLong("float_snoozed_until", 0)) }
        androidx.compose.runtime.DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                if (key == "float_snoozed_until") snoozedUntil = changed.getLong(key, 0)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        LaunchedEffect(snoozedUntil) {
            val remaining = snoozedUntil - System.currentTimeMillis()
            if (remaining > 0) { delay(remaining); snoozedUntil = 0 }
        }
        // Say exactly what is missing and go straight to the fix.
        val missing = when {
            !setup.microphone -> R.string.ux_microphone_access
            !setup.overlay -> R.string.setup_float_overlay
            !setup.dictationService -> R.string.setup_float_a11y
            else -> null
        }
        val noSpeech = missing == null && !setup.speech
        val snoozed = snoozedUntil > System.currentTimeMillis()
        FloatingButtonCard(
            enabled = enabled,
            busy = settingsBusy.value,
            status = when {
                !enabled -> getString(R.string.setup_float_off)
                missing != null -> getString(R.string.home_float_missing, getString(missing))
                noSpeech -> getString(R.string.home_float_no_speech)
                snoozed -> getString(R.string.task_snoozed)
                else -> getString(R.string.task_ready_status)
            },
            action = when {
                missing != null -> getString(R.string.task_resume_setup)
                noSpeech -> getString(R.string.ux_speech_setup)
                snoozed -> getString(R.string.float_resume)
                else -> null
            },
            onAction = {
                when {
                    missing != null -> onNavigate(18)
                    noSpeech -> onOpenProviders(1)
                    else -> startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_UNSNOOZE))
                }
            },
            onToggle = { setFloatingEnabled(s, it) },
            onOpen = { onNavigate(9) },
        )
    }

    /** Providers: speech recognition and text cleanup as two tabs. */
    @Composable
    private fun ProvidersScreen(s: JSONObject, tab: Int, onTab: (Int) -> Unit) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = if (tab == 2) 1 else 0) { 2 }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // The requested tab wins (Activity links, deep links, taps): the saved
        // pager page must not overwrite it on entry, so only later swipes report back.
        LaunchedEffect(tab) { if (pager.currentPage != tab - 1) pager.scrollToPage(tab - 1) }
        val currentTab by androidx.compose.runtime.rememberUpdatedState(tab)
        LaunchedEffect(pager) {
            // drop(1): the restored page on entry is not a swipe.
            androidx.compose.runtime.snapshotFlow { pager.settledPage }.drop(1)
                .collect { page -> if (page != currentTab - 1) onTab(page + 1) }
        }
        Column(Modifier.fillMaxSize()) {
            Centered { TabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = pager.currentPage == 0, onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.providers_speech)) })
                Tab(selected = pager.currentPage == 1, onClick = { scope.launch { pager.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.tab_cleanup)) })
            } }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
                Centered(Modifier.fillMaxHeight()) { if (page == 1) CleanupScreen(s) else SttScreen(s) }
            }
        }
    }

    @Composable
    private fun SettingsHome(onNavigate: (Int) -> Unit) {
        TaskPage {
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                DestinationRow(getString(R.string.settings_guide), getString(R.string.settings_guide_sub), { onNavigate(16) })
            }
            SettingsGroup(getString(R.string.setup_float)) {
                DestinationRow(getString(R.string.task_button_controls), getString(R.string.task_button_description), { onNavigate(9) })
                DestinationRow(getString(R.string.setup_mic_button), getString(R.string.new_behavior_description), { onNavigate(10) })
            }
            SettingsGroup(getString(R.string.task_library_sync)) {
                DestinationRow(getString(R.string.ux_devices_sync), getString(R.string.task_sync_description), { onNavigate(6) })
            }
            SettingsGroup(getString(R.string.task_app)) {
                DestinationRow(getString(R.string.new_notifications), getString(if (notificationsAllowed.value) R.string.new_notifications_enabled else R.string.new_notifications_description), { onNavigate(8) })
                DestinationRow(getString(R.string.new_appearance), getString(R.string.new_appearance_description), { onNavigate(11) })
                DestinationRow(getString(R.string.diag_title), getString(R.string.new_diagnostics_description), { onNavigate(13) })
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    private fun safePackFingerprint(files: () -> List<File>): String? =
        try { LocalModelDownload.packFingerprint(files()) } catch (_: Exception) { null }

    @Composable
    private fun bubbleSetupState(s: JSONObject, assumeWhileVerifying: Boolean = false): BubbleSetupState {
        val prefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        val entries = s.optJSONObject("stt")?.optJSONArray("entries")
        val localEnabled = entries?.let {
            (0 until it.length()).any { i ->
                val entry = it.optJSONObject(i)
                entry?.optString("kind") == "Local" && entry.optBoolean("enabled", true)
            }
        } ?: false
        var selectedId by remember { mutableStateOf(LocalStt.selectedEntry(prefs).id) }
        androidx.compose.runtime.DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == LocalSttModels.PREF_SELECTED_MODEL) selectedId = LocalStt.selectedEntry(prefs).id
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val selected = LocalSttModels.findById(selectedId)
        val changes = sttPackChanges.intValue
        val fingerprint = safePackFingerprint { sttPackFiles(filesDir, selected) }
        val localVerified = rememberVerifiedPack(
            selected.id, fingerprint, changes, sttVerifyCache,
            currentFingerprint = { safePackFingerprint { sttPackFiles(filesDir, selected) } },
            stillSelected = { LocalStt.selectedEntry(prefs).id == selected.id },
            isVerified = { it.state == LocalModelDownload.RowState.PRESENT },
            verify = { LocalSttModels.inspect(filesDir, selected) },
            whilePending = assumeWhileVerifying,
        )

        return BubbleSetupState(micGranted.value, overlayGranted.value, a11yOn.value,
            isSpeechConfigured(isServerConfigured(s), localEnabled, localVerified))
    }

    private fun setFloatingEnabled(s: JSONObject, enabled: Boolean) {
        val prefs = s.optJSONObject("preferences") ?: JSONObject()
        prefs.put("float_enabled", enabled); s.put("preferences", prefs)
        save()
    }

    /** True when the always-on status channel is switched off in Android settings. */
    private fun statusChannelHidden(): Boolean = SettingsGuide.granted(this, SettingsGuide.Step.STATUS_CHANNEL)

    /**
     * First run: welcome, button setup (required: the pager stops there until
     * microphone, overlay and dictation service are granted), optional
     * notifications, then practice with the real floating button. Finishing
     * lands on the floating button settings.
     */
    @Composable
    private fun OnboardingScreen(s: JSONObject, onFinish: () -> Unit) {
        val setup = bubbleSetupState(s)
        val permissionsDone = setup.microphone && setup.overlay && setup.dictationService
        val pages = if (permissionsDone) 4 else 2
        val pager = androidx.compose.foundation.pager.rememberPagerState { pages }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun go(page: Int) { scope.launch { pager.animateScrollToPage(page) } }
        BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }
        Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { p ->
                Centered(Modifier.fillMaxHeight()) { TaskPage {
                    when (p) {
                        0 -> {
                            OnboardingHeading(getString(R.string.task_onboarding_title))
                            Text(getString(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(20.dp))
                            listOf(R.string.onb_welcome_point_1, R.string.onb_welcome_point_2, R.string.onb_welcome_point_3).forEach {
                                Row(Modifier.padding(vertical = 6.dp)) {
                                    Text("•", Modifier.width(20.dp), color = MaterialTheme.colorScheme.primary)
                                    Text(getString(it), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        1 -> ButtonSetupContent(setup)
                        2 -> OnboardingNotificationsPage()
                        else -> OnboardingPracticePage(active = pager.currentPage == 3, s = s)
                    }
                    Spacer(Modifier.height(24.dp))
                } }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val buttonShown by OverlayService.practiceButtonShown.collectAsState()
            StepDots(4, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
            Centered { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) TextButton(onClick = { go(pager.currentPage - 1) }) { Text(getString(R.string.new_back)) }
                Spacer(Modifier.weight(1f))
                when {
                    // Finishing unlocks once the real button has appeared on the practice page.
                    pager.currentPage == 3 -> Button(onClick = onFinish, enabled = buttonShown) { Text(getString(R.string.onb_finish), maxLines = 1) }
                    pager.currentPage == 1 && !permissionsDone -> Button(onClick = {}, enabled = false) { Text(getString(R.string.onb_next)) }
                    else -> Button(onClick = { go(pager.currentPage + 1) }) {
                        Text(getString(if (pager.currentPage == 0) R.string.task_get_started else R.string.onb_next))
                    }
                }
            } }
        }
        MicRationaleDialog()
    }

    @Composable
    private fun MicRationaleDialog() {
        if (!micRationale.value) return
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

    @Composable
    private fun OnboardingHeading(text: String) {
        Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
    }

    /** The three grants the floating button needs, one at a time. Speech providers are set up separately. */
    @Composable
    private fun ButtonSetupContent(setup: BubbleSetupState) {
        OnboardingHeading(getString(R.string.onb_setup_title))
        Text(getString(R.string.onb_setup_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val steps = listOf(setup.microphone to R.string.ux_microphone_access, setup.overlay to R.string.setup_float_overlay,
            setup.dictationService to R.string.setup_float_a11y)
        SettingsGroup(getString(R.string.onb_setup_progress, steps.count { it.first })) {
            Column(Modifier.padding(16.dp)) {
                steps.forEach { (done, label) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (done) "✓" else "○", Modifier.width(24.dp),
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(getString(label), color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(16.dp))
                when {
                    !setup.microphone -> {
                        Text(getString(R.string.task_mic_reason))
                        Text(getString(R.string.onb_mic_always), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { if (micBlocked.value) openAppSettings() else micPerm.launch(Manifest.permission.RECORD_AUDIO) }) { Text(getString(R.string.setup_mic_grant)) }
                    }
                    !setup.overlay -> {
                        Text(getString(R.string.task_overlay_reason))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openSettingsAndReturn(SettingsGuide.Step.OVERLAY) }) { Text(getString(R.string.setup_float_overlay)) }
                        SettingsHint(R.string.guide_hint_overlay)
                    }
                    !setup.dictationService -> {
                        Text(getString(R.string.control_service_help))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openSettingsAndReturn(SettingsGuide.Step.ACCESSIBILITY) }) { Text(getString(R.string.setup_float_a11y)) }
                        // Without picture-in-picture the guide's reassurance moves here.
                        if (!SettingsGuide.pipAllowed(this@MainActivity)) SettingsHint(R.string.guide_hint_a11y)
                        if (SettingsGuide.restrictedLikely(this@MainActivity)) {
                            TextButton(onClick = { openSettingsAndReturn(SettingsGuide.Step.RESTRICTED) }) {
                                Text(getString(R.string.guide_restricted_link))
                            }
                            SettingsHint(R.string.guide_hint_restricted)
                        }
                    }
                    else -> Text(getString(R.string.onb_setup_complete), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    /** "Finish setup" from Activity: the same three steps outside onboarding. */
    @Composable
    private fun SetupScreen(s: JSONObject) {
        val setup = bubbleSetupState(s)
        TaskPage { ButtonSetupContent(setup) }
        MicRationaleDialog()
    }

    /**
     * Practice with the real floating button over a real text field. Only
     * the transcription is fake ([OverlayService.practiceMode]), and only
     * while this page is visible and the app is in the foreground.
     */
    @Composable
    private fun OnboardingPracticePage(active: Boolean, s: JSONObject) {
        val floatOn = s.optJSONObject("preferences")?.optBoolean("float_enabled", true) ?: true
        if (active) {
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                OverlayService.practiceMode = true
                onPauseOrDispose { OverlayService.practiceMode = false }
            }
        }
        OnboardingHeading(getString(R.string.onb_try_title))
        Text(getString(R.string.onb_try_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        listOf(R.string.onb_try_point_1, R.string.onb_try_point_2, R.string.onb_try_point_3).forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", Modifier.width(20.dp), color = MaterialTheme.colorScheme.primary)
                Text(getString(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!floatOn) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(getString(R.string.onb_try_float_off), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { setFloatingEnabled(s, true) }) { Text(getString(R.string.onb_try_turn_on)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        var practice by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = practice, onValueChange = { practice = it },
            placeholder = { Text(getString(R.string.tutorial_placeholder)) },
            label = { Text(getString(R.string.tutorial_app_name)) },
            minLines = 4,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(getString(R.string.onb_try_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val shown by OverlayService.practiceButtonShown.collectAsState()
        if (!shown) {
            Text(getString(R.string.onb_try_locked), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun OnboardingNotificationsPage() {
        @Suppress("UNUSED_EXPRESSION") resumeTick.intValue // re-read the channel after returning from Android settings
        val hidden = statusChannelHidden()
        OnboardingHeading(getString(R.string.onb_notif_title))
        Text(getString(R.string.onb_notif_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        listOf(R.string.onb_notif_point_1, R.string.onb_notif_point_2, R.string.onb_notif_point_3).forEach {
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("•", Modifier.width(20.dp), color = MaterialTheme.colorScheme.primary)
                Text(getString(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!notificationsAllowed.value) {
            Button(onClick = { requestNotificationPermission() }) { Text(getString(R.string.new_enable_notifications)) }
            Text(getString(R.string.onb_notif_optional), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("✓ " + getString(R.string.new_notifications_enabled), color = MaterialTheme.colorScheme.primary)
            SettingsGroup(getString(R.string.onb_notif_quiet_title)) {
                Column(Modifier.padding(16.dp)) {
                    Text(getString(R.string.onb_notif_quiet_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    if (hidden) {
                        Text("✓ " + getString(R.string.onb_notif_quiet_done), color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedButton(onClick = { openSettingsAndReturn(SettingsGuide.Step.STATUS_CHANNEL) }) {
                            Text(getString(R.string.onb_notif_quiet_action))
                        }
                        SettingsHint(R.string.guide_hint_channel)
                    }
                }
            }
        }
    }

    @Composable
    private fun BubbleScreen(s: JSONObject, onBehavior: () -> Unit) {
        val setupPrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        val hapticView = LocalView.current
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.setup_float)) {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                val on = prefs.optBoolean("float_enabled", true)
                Text(
                    stringResource(R.string.setup_float_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.setup_float), modifier = Modifier.weight(1f))
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.setup_float) }, checked = on, onCheckedChange = {
                        setFloatingEnabled(s, it)
                    })
                }
                TextButton(onClick = onBehavior) { Text(getString(R.string.setup_mic_button)) }
                // Foldables (#113): optional separate settings per screen. Off = one shared set, as before.
                val foldable = remember { FoldScreens.supported(setupPrefs) }
                var split by remember { mutableStateOf(foldable && FoldScreens.split(setupPrefs)) }
                // Per-screen settings edit the screen in use, so the preview matches the real button.
                // The activity is recreated on fold/unfold, so this stays current.
                val screen = remember { FoldScreens.current(this@MainActivity) }
                val screenKey: (String) -> String = { FoldScreens.key(it, screen, split) }
                if (foldable) {
                    SplitScreensSetting(split) {
                        split = it
                        FoldScreens.setSplit(setupPrefs, it)
                        OverlayService.start(this@MainActivity)
                    }
                }
                if (split) {
                    val inner = screen == FoldScreens.Screen.INNER
                    Text(stringResource(if (inner) R.string.fold_inner else R.string.fold_cover), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                    Text(stringResource(if (inner) R.string.fold_to_cover else R.string.fold_to_inner), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Optional grip (default off): the mic drags itself.
                val grabKey = screenKey("float_grab")
                var grab by remember(grabKey) {
                    mutableStateOf(setupPrefs.getBoolean(grabKey, false))
                }
                // Grab point on the other screen, for the hold-delay hint below.
                val otherGrab = remember(grab, screen, split) {
                    setupPrefs.getBoolean(FoldScreens.key("float_grab", FoldScreens.other(screen), split), false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.setup_float_grab),
                        modifier = Modifier.weight(1f),
                    )
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.setup_float_grab) }, checked = grab, onCheckedChange = {
                        grab = it
                        // Keep the stored idle choice; dot choices pause while the grip is on and return after.
                        setupPrefs.edit { putBoolean(grabKey, it) }
                        // Rebuild the bubble chrome for the new mode.
                        OverlayService.start(this@MainActivity)
                    })
                }
                Text(
                    stringResource(R.string.setup_float_grab_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BubbleAppearanceSettings(setupPrefs, grab, screenKey) { OverlayService.start(this@MainActivity) }
                if (split) {
                    // Idle delay is per screen, so it leaves Advanced while the screens are separate.
                    Spacer(Modifier.height(8.dp))
                    IdleDelaySetting(setupPrefs, grab, screenKey) { OverlayService.start(this@MainActivity) }
                    Spacer(Modifier.height(8.dp))
                    PositionSetting(setupPrefs, screenKey) { OverlayService.start(this@MainActivity) }
                    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Text(stringResource(R.string.fold_both), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
                }
                var upperLock by remember { mutableStateOf(setupPrefs.getBoolean("float_lock_enabled", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.float_lock_enabled), modifier = Modifier.weight(1f))
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.float_lock_enabled) }, checked = upperLock, onCheckedChange = {
                        upperLock = it
                        setupPrefs.edit { putBoolean("float_lock_enabled", it) }
                        OverlayService.start(this@MainActivity)
                    })
                }
                var snoozedUntil by remember { mutableStateOf(setupPrefs.getLong("float_snoozed_until", 0)) }
                androidx.compose.runtime.DisposableEffect(setupPrefs) {
                    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                        if (key == "float_snoozed_until") snoozedUntil = prefs.getLong(key, 0)
                    }
                    setupPrefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { setupPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
                }
                LaunchedEffect(snoozedUntil) {
                    val remaining = snoozedUntil - System.currentTimeMillis()
                    if (remaining > 0) {
                        kotlinx.coroutines.delay(remaining)
                        snoozedUntil = 0
                    }
                }
                Reveal(snoozedUntil > System.currentTimeMillis()) {
                    TextButton(onClick = {
                        startService(Intent(this@MainActivity, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_UNSNOOZE))
                    }) { Text(stringResource(R.string.float_resume)) }
                }
                var bubbleOpacity by remember { mutableIntStateOf(setupPrefs.getInt("float_opacity", 65).coerceIn(30, 100)) }
                Text(getString(R.string.setup_float_opacity), style = MaterialTheme.typography.bodyMedium)
                Text("$bubbleOpacity%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = bubbleOpacity.toFloat(),
                    onValueChange = { bubbleOpacity = it.roundToInt() },
                    valueRange = 30f..100f,
                    modifier = Modifier.semantics { contentDescription = getString(R.string.setup_float_opacity); stateDescription = "$bubbleOpacity%" },
                    onValueChangeFinished = {
                        setupPrefs.edit { putInt("float_opacity", bubbleOpacity) }
                        OverlayService.start(this@MainActivity)
                    },
                )
                var advanced by rememberSaveable { mutableStateOf(false) }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                ExpandHeader(stringResource(R.string.bubble_advanced), stringResource(R.string.bubble_advanced_sub), advanced,
                    { advanced = !advanced }, Modifier.bleedHorizontal(16.dp))
                Reveal(advanced) {
                    var followKeyboard by remember { mutableStateOf(setupPrefs.getBoolean("float_follow_keyboard", true)) }
                    ChoiceSetting(getString(R.string.float_visibility),
                        getString(if (followKeyboard) R.string.float_follow_keyboard else R.string.float_follow_focus),
                        listOf(getString(R.string.float_follow_keyboard), getString(R.string.float_follow_focus))) { picked ->
                        followKeyboard = picked == getString(R.string.float_follow_keyboard)
                        setupPrefs.edit { putBoolean("float_follow_keyboard", followKeyboard) }
                        OverlayService.start(this@MainActivity)
                    }
                    var resident by remember { mutableStateOf(setupPrefs.getBoolean("float_keep_attached", false)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.bubble_resident), Modifier.weight(1f))
                        FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.bubble_resident) }, checked = resident, onCheckedChange = {
                            resident = it
                            setupPrefs.edit { putBoolean("float_keep_attached", it) }
                            OverlayService.start(this@MainActivity)
                        })
                    }
                    Text(stringResource(R.string.bubble_resident_help), style = MaterialTheme.typography.bodySmall)
                    var snoozeMinutes by remember { mutableIntStateOf(setupPrefs.getInt("float_snooze_minutes", 10)) }
                    ChoiceSetting(getString(R.string.float_snooze_duration), "$snoozeMinutes min",
                        listOf("5 min", "10 min", "15 min", "30 min", "60 min")) { picked ->
                        snoozeMinutes = picked.substringBefore(" ").toInt()
                        setupPrefs.edit { putInt("float_snooze_minutes", snoozeMinutes) }
                    }
                    ButtonShapeSetting(setupPrefs) { OverlayService.start(this@MainActivity) }
                    if (!split) {
                        Spacer(Modifier.height(8.dp))
                        IdleDelaySetting(setupPrefs, grab) { OverlayService.start(this@MainActivity) }
                    }
                    run {
                        // No grip: a finger that stays put this long records;
                        // moving sooner means a drag (never a dictation).
                        // With the grip it does not apply: greyed out (value kept) instead of hidden, so nothing jumps.
                        // Separate screens with Grab point on only one: the delay applies on the other one.
                        val grabEverywhere = grab && (!split || otherGrab)
                        val delayHint = when {
                            !split || grab == otherGrab -> null
                            (if (grab) FoldScreens.other(screen) else screen) == FoldScreens.Screen.COVER -> R.string.setup_float_delay_cover_only
                            else -> R.string.setup_float_delay_inner_only
                        }
                        val delayAlpha = if (grabEverywhere) 0.38f else 1f
                        var delayMs by remember {
                            mutableIntStateOf(
                                FloatState.pressDelayMs(
                                    setupPrefs.getInt(
                                        "float_press_delay",
                                        FloatState.PRESS_DELAY_DEFAULT_MS,
                                    ),
                                ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val delayOptions = FloatState.PRESS_DELAY_OPTIONS_MS
                        Text(getString(R.string.setup_float_delay), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = delayAlpha))
                        Text("$delayMs ms", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = delayAlpha))
                        Slider(
                            value = delayOptions.indexOf(delayMs).coerceAtLeast(0).toFloat(),
                            valueRange = 0f..delayOptions.lastIndex.toFloat(),
                            steps = delayOptions.size - 2,
                            enabled = !grabEverywhere,
                            onValueChange = {
                                val next = delayOptions[it.roundToInt().coerceIn(delayOptions.indices)]
                                if (next != delayMs) {
                                    delayMs = next
                                    ViewCompat.performHapticFeedback(hapticView, HapticFeedbackConstantsCompat.SEGMENT_TICK)
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = getString(R.string.setup_float_delay); stateDescription = "$delayMs ms" },
                            onValueChangeFinished = {
                                setupPrefs.edit { putInt("float_press_delay", delayMs) }
                                OverlayService.start(this@MainActivity)
                            },
                        )
                        StableText(listOf(stringResource(R.string.setup_float_delay_sub), stringResource(R.string.setup_float_delay_off)) +
                            listOfNotNull(delayHint?.let { stringResource(it) }),
                            if (delayHint != null) 2 else if (grabEverywhere) 1 else 0, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    /** One line before a Settings jump: what to toggle, and that the app comes back by itself. */
    @Composable
    private fun SettingsHint(text: Int) {
        Text(getString(text), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    private fun openNotificationChannel(channel: String) {
        AppNotificationChannels.ensure(this)
        startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel))
    }

    @Composable
    private fun NotificationSettings() {
        TaskPage {
            Text(getString(R.string.ux_notifications_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!notificationsAllowed.value) {
                SettingsGroup(getString(R.string.ux_resume_shortcut_title)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(getString(R.string.ux_resume_shortcut_help), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { requestNotificationPermission() }) {
                            Text(getString(R.string.new_enable_notifications))
                        }
                    }
                }
            }
            SettingsGroup(getString(R.string.ux_notification_choices)) {
                DestinationRow(getString(R.string.ux_snooze_reminder_title), getString(R.string.ux_snooze_reminder_help)) {
                    openNotificationChannel(AppNotificationChannels.FLOAT_CONTROLS)
                }
                DestinationRow(getString(R.string.ux_hide_updates_title), getString(R.string.ux_hide_updates_help)) {
                    // Auto-return only toward hiding; already hidden means the user is here to undo it.
                    if (statusChannelHidden()) openNotificationChannel(NotifPolicy.UNIFIED_CHANNEL)
                    else openSettingsAndReturn(SettingsGuide.Step.STATUS_CHANNEL)
                }
            }
        }
    }

    @Composable
    private fun MicrophoneSettings(s: JSONObject) {
        val prefs = s.optJSONObject("preferences") ?: JSONObject()
        val hotkeys = s.optJSONObject("hotkeys") ?: JSONObject()
        val stt = s.optJSONObject("stt") ?: JSONObject()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.mic_press_title)) {
                val modes = listOf("Smart", "Push", "Tap")
                val labels = listOf(getString(R.string.control_smart), getString(R.string.mic_mode_hold), getString(R.string.mic_mode_tap))
                var mode by remember { mutableStateOf(prefs.optString("mic_mode", "Smart").takeIf { it in modes } ?: "Smart") }
                ChoiceSetting(getString(R.string.setup_behavior_label), labels[modes.indexOf(mode)], labels) { picked ->
                    mode = modes[labels.indexOf(picked)]
                    prefs.put("mic_mode", mode)
                    s.put("preferences", prefs)
                    save()
                }
                Text(
                    stringResource(when (mode) {
                        "Push" -> R.string.setup_mode_push
                        "Tap" -> R.string.setup_mode_tap
                        else -> R.string.setup_mode_smart
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                // Always shown so the layout never jumps; greyed out outside Smart.
                val tapOptions = listOf(100L, 150L, 200L, 250L, 300L, 400L, 500L)
                val tapMs = hotkeys.optLong("smart_tap_ms", 150L)
                OptionSlider(
                    label = getString(R.string.mic_tap_limit),
                    options = tapOptions,
                    selected = tapOptions.minBy { kotlin.math.abs(it - tapMs) },
                    valueText = { getString(R.string.mic_ms, it) },
                    enabled = mode == "Smart",
                    help = getString(if (mode == "Smart") R.string.mic_tap_limit_sub else R.string.mic_tap_limit_off),
                ) {
                    hotkeys.put("smart_tap_ms", it)
                    s.put("hotkeys", hotkeys)
                    save()
                }
            }
            MicPrioritySection(s)
            Section(stringResource(R.string.mic_recording_title)) {
                val delayOptions = listOf(0L, 100L, 250L, 500L, 1000L)
                val delayMs = stt.optLong("release_delay_ms", 0L)
                OptionSlider(
                    label = getString(R.string.stt_delay),
                    options = delayOptions,
                    selected = delayOptions.minBy { kotlin.math.abs(it - delayMs) },
                    valueText = { if (it == 0L) getString(R.string.stt_delay_off) else getString(R.string.mic_ms, it) },
                    help = getString(R.string.stt_delay_sub),
                ) {
                    stt.put("release_delay_ms", it)
                    s.put("stt", stt)
                    save()
                }
            }
        }
    }

    /**
     * Ranked input kinds (`preferences.mic_priority`); capture uses the first one
     * connected. Drag the handle to reorder; TalkBack gets move up/down actions.
     */
    @Composable
    private fun MicPrioritySection(s: JSONObject) {
        val prefs = s.optJSONObject("preferences") ?: JSONObject()
        val defaults = listOf(MicRoute.WIRED, MicRoute.BUILT_IN, MicRoute.BLUETOOTH)
        val saved = prefs.optJSONArray("mic_priority")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        var order by remember { mutableStateOf(saved.filter { it in defaults }.distinct() + defaults.filterNot { it in saved }) }
        var dragged by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        val heights = remember { mutableMapOf<String, Int>() }
        val labels = mapOf(
            MicRoute.WIRED to (R.string.mic_kind_wired to R.string.mic_kind_wired_sub),
            MicRoute.BUILT_IN to (R.string.mic_kind_builtin to R.string.mic_kind_builtin_sub),
            MicRoute.BLUETOOTH to (R.string.mic_kind_bluetooth to R.string.mic_kind_bluetooth_sub),
        )
        fun swap(from: Int, to: Int) {
            order = order.toMutableList().apply { add(to, removeAt(from)) }
        }
        fun persist() {
            prefs.put("mic_priority", JSONArray(order))
            s.put("preferences", prefs)
            save()
        }
        Section(stringResource(R.string.mic_priority_title)) {
            Text(
                stringResource(R.string.mic_priority_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            order.forEachIndexed { i, kind ->
                key(kind) {
                    val (title, sub) = labels.getValue(kind)
                    val name = stringResource(title)
                    val up = getString(R.string.mic_priority_up, name)
                    val down = getString(R.string.mic_priority_down, name)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .zIndex(if (dragged == kind) 1f else 0f)
                            .graphicsLayer { translationY = if (dragged == kind) dragOffset else 0f }
                            .onSizeChanged { heights[kind] = it.height }
                            .padding(top = 8.dp)
                            .semantics {
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction(up) { swap(i, i - 1); persist(); true }.takeIf { i > 0 },
                                    CustomAccessibilityAction(down) { swap(i, i + 1); persist(); true }.takeIf { i < order.lastIndex },
                                )
                            },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. $name")
                            Text(stringResource(sub), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(12.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { dragged = kind; dragOffset = 0f },
                                        onDragEnd = { dragged = null; dragOffset = 0f; persist() },
                                        onDragCancel = { dragged = null; dragOffset = 0f; persist() },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // Swap once the row passes its neighbor's midpoint.
                                        val at = order.indexOf(kind)
                                        val next = order.getOrNull(at + 1)?.let { heights[it] }
                                        val prev = order.getOrNull(at - 1)?.let { heights[it] }
                                        if (next != null && dragOffset > next / 2f) {
                                            swap(at, at + 1)
                                            dragOffset -= next
                                        } else if (prev != null && dragOffset < -prev / 2f) {
                                            swap(at, at - 1)
                                            dragOffset += prev
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppearanceSettings(s: JSONObject) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.setup_prefs)) {
                val prefs = s.optJSONObject("preferences") ?: JSONObject()
                val themes = listOf("System", "Light", "Dark")
                val labels = listOf(getString(R.string.control_system), getString(R.string.control_light), getString(R.string.control_dark))
                ChoiceSetting(getString(R.string.setup_theme), labels[themes.indexOf(prefs.optString("theme", "System")).coerceAtLeast(0)], labels) { selected ->
                    prefs.put("theme", themes[labels.indexOf(selected)])
                    s.put("preferences", prefs)
                    save()
                }
                ToggleSetting(getString(R.string.ux_black_background), getString(R.string.ux_black_theme_help),
                    prefs.optBoolean("black_background", false)) {
                    prefs.put("black_background", it)
                    s.put("preferences", prefs)
                    save()
                }
            }
            Section(stringResource(R.string.icon_style_title)) {
                LauncherIconSettings()
            }
        }
    }

    /** Collected on open (off the UI thread); copy or share it for support. */
    @Composable
    private fun DiagnosticsSettings() {
        var diag by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            diag = kotlinx.coroutines.withContext(Dispatchers.IO) { collectDiagnostics() }
        }
        TaskPage {
            Text(stringResource(R.string.diag_intro), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { diag?.let { copyText(it) } }, enabled = diag != null) { Text(stringResource(R.string.diag_copy)) }
                OutlinedButton(onClick = {
                    diag?.let {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, it), null))
                    }
                }, enabled = diag != null) { Text(stringResource(R.string.diag_share)) }
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                val text = diag
                if (text == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
            toast(getString(localUnavailableRes()))
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
        // bytes from a single SHA pass — never two full hash sweeps),
        // computed OFF the composition thread: pack hashes (up to GBs)
        // on the UI thread ANR the app and drop input. Rows read
        // missing until the first pass lands.
        val sttPackCache = sttVerifyCache
        var modelInspects by remember {
            mutableStateOf<Map<String, LocalSttModels.PackInspect>>(emptyMap())
        }
        val sttChanges = sttPackChanges.intValue
        var sttFingerprints by remember(sttChanges) { mutableStateOf<Map<String, String>>(emptyMap()) }
        LaunchedEffect(version, sttChanges) {
            modelInspects = emptyMap()
            sttFingerprints = emptyMap()
            for (m in LocalSttModels.MODELS) {
                val fp = safePackFingerprint { sttPackFiles(filesDir, m) } ?: continue
                val result = runInterruptible(Dispatchers.IO) {
                    try {
                        sttPackCache.getStable(m.id, fp, { safePackFingerprint { sttPackFiles(filesDir, m) } }) {
                            LocalSttModels.inspect(filesDir, m)
                        }
                    } catch (_: Exception) { null }
                }
                if (fp != safePackFingerprint { sttPackFiles(filesDir, m) }) {
                    version++
                    break
                }
                sttFingerprints = sttFingerprints + (m.id to fp)
                modelInspects = modelInspects + (m.id to (result ?: LocalSttModels.PackInspect(LocalModelDownload.RowState.MISSING, 0L)))
            }
        }
        fun packOf(id: String): LocalPackState {
            val model = LocalSttModels.MODELS.firstOrNull { it.id == id } ?: return LocalPackState.MISSING
            val fp = safePackFingerprint { sttPackFiles(filesDir, model) } ?: return LocalPackState.MISSING
            if (sttFingerprints[id] != fp) return LocalPackState.MISSING
            return modelInspects[id]?.let { packStateForRow(it.state) } ?: LocalPackState.MISSING
        }
        fun bytesOf(id: String): Long =
            modelInspects[id]?.verifiedBytes ?: 0L
        val selPack = packOf(selectedModel.id)
        // No on-device runtime (not arm64 / no pack URL): the chain toggle and all
        // Download buttons gate on this (fail-closed, no download offered).
        val sttRunner = remember { sttRunnerSupported() }
        // Per-model in-row download state (desktop fill_stt_models
        // parity: progress updates in place, Retry on failure).
        var dlBusy by remember { mutableStateOf(setOf<String>()) }
        var dlProg by remember { mutableStateOf(mapOf<String, Pair<Long, Long>>()) }
        var dlErr by remember { mutableStateOf(mapOf<String, String>()) }
        var deleteConfirm by remember { mutableStateOf<String?>(null) }
        fun rowOf(m: LocalSttModels.Entry): LocalModelDownload.RowState {
            val checking = try {
                sttFingerprints[m.id] != LocalModelDownload.packFingerprint(sttPackFiles(filesDir, m))
            } catch (_: Exception) {
                false
            }
            return LocalModelDownload.resolve(
                packOf(m.id) == LocalPackState.PRESENT,
                packOf(m.id) == LocalPackState.MISMATCH,
                dlBusy.contains(m.id),
                dlErr[m.id],
                packOf(m.id) == LocalPackState.INCOMPLETE,
                checking = checking,
            )
        }
        fun trackModelDownload(m: LocalSttModels.Entry, resume: Boolean) {
            observeDownloadWork(
                kind = ModelDownloadSchedule.KIND_STT,
                id = m.id,
                onProgress = { r, t ->
                    dlProg = dlProg + (m.id to (r to (t ?: LocalSttModels.totalBytes(m))))
                },
                onDone = { dlBusy = dlBusy - m.id; dlProg = dlProg - m.id; version++ },
                onError = { msg ->
                    dlBusy = dlBusy - m.id
                    dlErr = dlErr + (m.id to msg)
                    version++
                },
                onActive = { if (!dlBusy.contains(m.id)) dlBusy = dlBusy + m.id },
                resume = resume,
            )
        }
        fun startModelDownload(m: LocalSttModels.Entry) {
            // No runtime: downloads could never run — refuse fail-closed.
            if (!sttRunner) {
                toast(getString(localUnavailableRes()))
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
            trackModelDownload(m, resume = false)
        }
        // Downloads keep running in WorkManager while this screen is gone:
        // re-attach their rows (busy + real progress) on every entry.
        LaunchedEffect(Unit) { LocalSttModels.MODELS.forEach { trackModelDownload(it, resume = true) } }
        // Provider tests: ONE pattern on every chain row (same
        // button + latency/excerpt inline). Per-entry state keyed by
        // row id so reorder keeps results with their row.
        var sttLocalBusy by remember { mutableStateOf(false) }
        var sttLocalResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        var sttBusy by remember { mutableStateOf(setOf<String>()) }
        var sttResults by remember { mutableStateOf(mapOf<String, ProviderTest.Result>()) }
        fun probeSttServer(url: String, model: String, apiKey: String = ""): ProviderTest.Result =
            probeSttEndpoint(url, model, apiKey)
        fun startSttServerTest(e: JSONObject) {
            val id = e.optString("id")
            if (id.isBlank() || sttBusy.contains(id)) return
            val kind = e.optString("kind")
            val url = e.optString("url")
            val model = e.optString("model")
            val apiKey = e.optString("api_key")
            if (!sttServerTestAllowed(kind, url, model)) return
            sttBusy = sttBusy + id
            sttResults = sttResults - id
            workers.execute {
                val result = try {
                    probeSttServer(url, model, apiKey)
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
                        // Reference speech when the debug asset ships
                        // (real recorded speech matched to the model:
                        // English asset for English-only entries, else
                        // Spanish — a blank decode FAILS); sine probe
                        // otherwise (pipeline-only, blank is expected
                        // → "pack ok"). See [SampleSpeech].
                        val refPcm = SampleSpeech.loadAsset(this, SampleSpeech.assetFor(selectedModel))
                        val probePcm = refPcm ?: ProviderTest.samplePcm16()
                        val r = LocalStt.transcribe(
                            this, probePcm, 16000,
                            stt.optString("language", "auto").ifBlank { "auto" },
                        ) ?: throw IllegalStateException("decode skipped — engine unavailable")
                        val text = r.text.trim()
                        if (text.isNotBlank()) text
                        else if (refPcm != null) {
                            throw IllegalStateException("blank decode on reference speech")
                        } else {
                            "pack ok · ${r.chunks} chunks · ${r.decodeMs}ms"
                        }
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
            Section(stringResource(R.string.providers_language_title)) {
                Text(stringResource(R.string.providers_language_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val language = stt.optString("language", "auto")
                DropDown(getString(R.string.stt_language), langs.firstOrNull { it.first == language }?.second ?: language,
                    langs.map { it.second }) { label ->
                    stt.put("language", langs.first { it.second == label }.first); save(); version++
                }
            }
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
                    val local = e.optString("kind") == "Local"
                    val id = e.optString("id")
                    val localStatus = if (!sttRunner) getString(localUnavailableRes()) else when (selPack) {
                            LocalPackState.PRESENT -> getString(R.string.local_pack_present)
                            LocalPackState.MISSING -> getString(R.string.local_pack_missing)
                            LocalPackState.MISMATCH -> getString(R.string.local_pack_mismatch)
                            LocalPackState.INCOMPLETE -> getString(R.string.local_downloading, selectedModel.name)
                        }
                    ProviderEntryCard(
                        entry = e,
                        summary = entrySummary(e),
                        onEdit = { if (local) localInfo = true else dialog = JSONObject(e.toString()) },
                        onToggle = { next ->
                            if (local) onLocalToggle(e, next, selPack, { version++ }, sttRunner)
                            else { e.put("enabled", next); save(); version++ }
                        },
                        toggleEnabled = !local || sttRunner || e.optBoolean("enabled", true),
                        onDelete = {
                            val next = JSONArray()
                            for (j in 0 until entries.length()) {
                                if (entries.optJSONObject(j)?.optString("id") != id) next.put(entries.get(j))
                            }
                            stt.put("entries", next); save(); version++
                        },
                        handle = handle,
                        busy = if (local) sttLocalBusy else sttBusy.contains(id),
                        result = if (local) sttLocalResult else sttResults[id],
                        testEnabled = if (local) sttLocalTestAllowed(sttRunner, selPack) else sttServerTestAllowed(e.optString("kind"), e.optString("url"), e.optString("model")),
                        onTest = { if (local) startSttLocalTest() else startSttServerTest(e) },
                        disabledReason = if (local) { if (sttRunner) localTestDisabledReason(true, selPack == LocalPackState.PRESENT) else null } else sttTestDisabledReason(e.optString("kind"), e.optString("url"), e.optString("model")),
                        status = if (local) localStatus else null,
                        usingModel = if (local && sttRunner) getString(R.string.stt_using_model, selectedModel.name) else null,
                    )
                }
                if (entries.length() == 0) Text(stringResource(R.string.ux_provider_empty),
                    Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (!sttRunner) LocalUnavailableNote() else CollapsibleModelsSection(
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
                        stringResource(localUnavailableRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val captureLang = stt.optString("language", "auto").ifBlank { "auto" }
                val captureLabel = langs.firstOrNull { it.first == captureLang }?.second
                    ?: captureLang
                // Device total RAM for the flagship gate note (display only).
                val deviceRamMb = remember { totalRamMb() }
                // Provider filter pills (desktop local_provider
                // parity, UI-local: the active model selection is
                // untouched). Stored as the enum name (nullable =
                // All) so the value stays saveable.
                var sttProvider by rememberSaveable { mutableStateOf<String?>(null) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = sttProvider == null,
                        colors = primaryFilterChipColors(),
                        onClick = { sttProvider = null },
                        label = { Text(stringResource(R.string.stt_provider_all)) },
                    )
                    for (p in LocalSttModels.providers()) {
                        FilterChip(
                            selected = sttProvider == p.name,
                            colors = primaryFilterChipColors(),
                            onClick = { sttProvider = p.name },
                            label = { Text(LocalSttModels.providerLabel(p)) },
                        )
                    }
                }
                val providerFilter = sttProvider?.let { n ->
                    LocalSttModels.Provider.entries.firstOrNull { it.name == n }
                }
                for (m in LocalSttModels.forProvider(providerFilter)) {
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
                            if (m.freeRamMb > 0) {
                                Text(
                                    stringResource(
                                        R.string.stt_needs_free_ram,
                                        android.text.format.Formatter.formatShortFileSize(this@MainActivity, m.freeRamMb * 1_000_000L),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                                    LocalModelDownload.RowState.CHECKING ->
                                        stringResource(R.string.local_checking)
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
                            // Keep-warm policy (per-model resident slot).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.keep_label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                KeepDropdown(
                                    LocalKeepWarm.readKeepSecs(
                                        statePrefs,
                                        LocalKeepWarm.KIND_STT,
                                        m.id,
                                        LocalKeepWarm.INDEFINITE,
                                    ),
                                ) { picked ->
                                    LocalKeepWarm.writeKeepSecs(
                                        statePrefs,
                                        LocalKeepWarm.KIND_STT,
                                        m.id,
                                        picked,
                                    )
                                    if (picked == LocalKeepWarm.IMMEDIATE) {
                                        try {
                                            LocalStt.unload()
                                        } catch (_: Throwable) {
                                        }
                                    }
                                    version++
                                }
                            }
                            if (row == LocalModelDownload.RowState.DOWNLOADING ||
                                row == LocalModelDownload.RowState.INCOMPLETE ||
                                row == LocalModelDownload.RowState.CHECKING
                            ) {
                                Spacer(Modifier.height(4.dp))
                                val frac = if (row == LocalModelDownload.RowState.CHECKING) {
                                    null
                                } else if (row == LocalModelDownload.RowState.DOWNLOADING) {
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
                                    // No runtime: no Download offered (could
                                    // never run) — full-APK pointer instead.
                                    Text(
                                        stringResource(localUnavailableRes()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else when (row) {
                                    LocalModelDownload.RowState.DOWNLOADING -> {}
                                    LocalModelDownload.RowState.CHECKING -> {}
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
            // Language lives at the top; the rarely touched capture tuning stays folded away.
            CollapsibleModelsSection(stringResource(R.string.bubble_advanced), stringResource(R.string.stt_advanced_sub), "stt_advanced_expanded") {
                val vad = stt.optBoolean("vad_enabled", false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.stt_vad))
                        Text(stringResource(R.string.stt_vad_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.stt_vad) }, checked = vad, onCheckedChange = {
                        stt.put("vad_enabled", it); save(); version++
                    })
                }
                Spacer(Modifier.height(12.dp))
                var gate by remember { mutableStateOf(stt.optDouble("vad_threshold", 0.1).toFloat()) }
                Text(stringResource(R.string.stt_vad_gate, gate), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = gate, onValueChange = { gate = it },
                    onValueChangeFinished = {
                        stt.put("vad_threshold", gate.toDouble()); save()
                    },
                    valueRange = 0f..0.5f,
                )
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
                                stringResource(localUnavailableRes()),
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
        // Draft test: same ONE ProviderTestRow pattern as the chain
        // rows (same button + latency/excerpt inline), run against the
        // unsaved draft values. Draft edits clear the stale result.
        var draftBusy by remember { mutableStateOf(false) }
        var draftResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        val urlRequired = kind == "SelfHosted"
        val urlBlank = url.isBlank() && urlRequired
        val modelBlank = model.isBlank() && !isLocalKind
        val keyMissing = enabled && cloudCredentialsMissing(kind, url, key)
        val canSave = !urlBlank && !modelBlank && !singletonBlocked && !keyMissing && providerKindAvailable(kind, sttRunnerSupported())
        fun startDraftTest() {
            if (!sttServerTestAllowed(kind, url, model) || draftBusy) return
            draftBusy = true
            draftResult = null
            val u = url
            val m = model
            val k = key
            workers.execute {
                val result = try {
                    probeSttEndpoint(u, m, k)
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
                        ChoiceSetting(getString(R.string.llm_kind), kind, availableProviderKinds(listOf("SelfHosted", "Cloud", "Local"), sttRunnerSupported())) {
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
                            stringResource(if (sttRunnerSupported()) R.string.stt_local_note else localUnavailableRes()),
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
                        ApiKeyField(key, { key = it; draftResult = null },
                            isError = keyMissing, errorText = stringResource(R.string.task_api_key_required))
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
                            FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.stt_streaming) }, checked = streaming, onCheckedChange = { streaming = it })
                        }
                        Text(
                            stringResource(R.string.stt_streaming_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stt_enabled), modifier = Modifier.weight(1f))
                        FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.stt_enabled) }, checked = enabled, onCheckedChange = { enabled = it })
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
        // Per-asset verify runs OFF the composition thread: pack
        // hashes (up to GBs) on the UI thread ANR the app and drop
        // input (owner repro on a 2.6GB pack). Rows read missing
        // until the first pass lands, then flip as verified.
        val packCache = llmVerifyCache
        var llmInspects by remember { mutableStateOf<Map<String, LlmInspect>>(emptyMap()) }
        val llmChanges = llmPackChanges.intValue
        var llmFingerprints by remember(llmChanges) { mutableStateOf<Map<String, String>>(emptyMap()) }
        LaunchedEffect(version, llmChanges) {
            llmInspects = emptyMap()
            llmFingerprints = emptyMap()
            for (a in assets) {
                val fp = safePackFingerprint { llmPackFiles(filesDir, a) } ?: continue
                val result = runInterruptible(Dispatchers.IO) {
                    try {
                        packCache.getStable(a.id, fp, { safePackFingerprint { llmPackFiles(filesDir, a) } }) {
                            llmInspect(filesDir, a)
                        }
                    } catch (_: Exception) { null }
                }
                if (fp != safePackFingerprint { llmPackFiles(filesDir, a) }) {
                    version++
                    break
                }
                llmFingerprints = llmFingerprints + (a.id to fp)
                llmInspects = llmInspects + (a.id to (result ?: LlmInspect(LocalPackState.MISSING, 0L)))
            }
        }
        fun llmPackOf(id: String): LocalPackState {
            val asset = assets.firstOrNull { it.id == id } ?: return LocalPackState.MISSING
            val fp = safePackFingerprint { llmPackFiles(filesDir, asset) } ?: return LocalPackState.MISSING
            if (llmFingerprints[id] != fp) return LocalPackState.MISSING
            return llmInspects[id]?.state ?: LocalPackState.MISSING
        }
        fun llmBytesOf(id: String): Long =
            llmInspects[id]?.verifiedBytes ?: 0L
        val llmSelId = llmSelected?.id
        val llmSelPack = if (llmSelId != null) llmPackOf(llmSelId) else LocalPackState.MISSING
        // Per-asset in-row download state (desktop fill_stt_models
        // parity: progress updates in place, Retry on failure).
        var llmDlBusy by remember { mutableStateOf(setOf<String>()) }
        var llmDlProg by remember { mutableStateOf(mapOf<String, Pair<Long, Long?>>()) }
        var llmDlErr by remember { mutableStateOf(mapOf<String, String>()) }
        // Network posture for the constraint-blocked download message
        // (recomputed per composition; cheap binder call). When set,
        // a zero-progress DOWNLOADING row says "waiting" + offers
        // metered consent instead of a dead 0-byte bar.
        val netState = activeNetState()
        var llmDeleteConfirm by remember { mutableStateOf<String?>(null) }
        // Local LLM test (existing self-test harness, now through
        // the ONE [ProviderTest.Result] pattern) + remote LLM probes
        // (minimal ping, no heavy generate). Per-entry remote state
        // keyed by row id.
        var llmTestBusy by remember { mutableStateOf(false) }
        var llmTestResult by remember { mutableStateOf<ProviderTest.Result?>(null) }
        var llmBusy by remember { mutableStateOf(setOf<String>()) }
        var llmResults by remember { mutableStateOf(mapOf<String, ProviderTest.Result>()) }
        fun llmRowOf(a: LocalLlmManager.ModelAsset): LocalModelDownload.RowState {
            // Background verify still running for these exact bytes:
            // show CHECKING, never a stale MISSING.
            val checking = try {
                llmFingerprints[a.id] != LocalModelDownload.packFingerprint(llmPackFiles(filesDir, a))
            } catch (_: Exception) {
                false
            }
            return LocalModelDownload.resolve(
                llmPackOf(a.id) == LocalPackState.PRESENT,
                llmPackOf(a.id) == LocalPackState.MISMATCH,
                llmDlBusy.contains(a.id),
                llmDlErr[a.id],
                llmPackOf(a.id) == LocalPackState.INCOMPLETE,
                checking = checking,
            )
        }
        /** Verified file backing [a]: per-asset slot, else the adopted legacy slot. */
        fun llmActiveFile(a: LocalLlmManager.ModelAsset): File {
            val slot = llmTaskFileFor(filesDir, a)
            if (!isPlaceholderSha(a.sha256) && LocalLlmManager.verifySha256(slot, a.sha256)) {
                return slot
            }
            return LocalLlmManager.defaultTaskFile(filesDir)
        }
        // No on-device runtime (not arm64 / no pack URL): no Download offered.
        val llmRunner = remember { llmRunnerSupported() }
        fun trackLlmDownload(a: LocalLlmManager.ModelAsset, resume: Boolean) {
            observeDownloadWork(
                kind = ModelDownloadSchedule.KIND_LLM,
                id = a.id,
                onProgress = { r, t -> llmDlProg = llmDlProg + (a.id to (r to t)) },
                onDone = { llmDlBusy = llmDlBusy - a.id; llmDlProg = llmDlProg - a.id; version++ },
                onError = { msg ->
                    llmDlBusy = llmDlBusy - a.id
                    llmDlErr = llmDlErr + (a.id to msg)
                    version++
                },
                onActive = { if (!llmDlBusy.contains(a.id)) llmDlBusy = llmDlBusy + a.id },
                resume = resume,
            )
        }
        // Re-attach rows to downloads still running in WorkManager (see STT).
        LaunchedEffect(Unit) { assets.forEach { trackLlmDownload(it, resume = true) } }
        fun startLlmDownload(a: LocalLlmManager.ModelAsset) {
            // No runtime: downloads could never run — refuse fail-closed.
            if (!llmRunner) {
                toast(getString(localUnavailableRes()))
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
            trackLlmDownload(a, resume = false)
        }
        fun startLlmDownloadMetered(a: LocalLlmManager.ModelAsset) {
            // Explicit metered-data consent from the waiting row: same
            // busy/progress tracking as [startLlmDownload], but the
            // work runs on any network (CONNECTED, not UNMETERED).
            // Battery/storage constraints still apply.
            if (!llmRunner) {
                toast(getString(localUnavailableRes()))
                return
            }
            if (llmDlBusy.contains(a.id) || isPlaceholderSha(a.sha256)) return
            llmDlBusy = llmDlBusy + a.id
            llmDlErr = llmDlErr - a.id
            llmDlProg = llmDlProg + (a.id to (0L to a.sizeBytes.takeIf { it > 0 }))
            ModelDownloadManager.enqueueLlmAsset(this@MainActivity, a, metered = true)
            trackLlmDownload(a, resume = false)
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
                            generateFn = { f, _ ->
                                LocalLlmManager.generate(
                                    applicationContext,
                                    f,
                                    system,
                                    LocalLlmManager.SELFTEST_TRANSCRIPT,
                                    target,
                                )
                            },
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
        fun probeLlmRemote(kind: String, url: String, model: String, apiKey: String = ""): ProviderTest.Result =
            probeLlmEndpoint(kind, url, model, apiKey)
        fun startLlmRemoteTest(e: JSONObject) {
            val id = e.optString("id")
            if (id.isBlank() || llmBusy.contains(id)) return
            val kind = e.optString("kind")
            val url = e.optString("url")
            val model = e.optString("model")
            val apiKey = e.optString("api_key")
            if (!llmRemoteTestAllowed(kind, url, model)) return
            llmBusy = llmBusy + id
            llmResults = llmResults - id
            workers.execute {
                val result = try {
                    probeLlmRemote(kind, url, model, apiKey)
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
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.cleanup_enable) }, checked = on, onCheckedChange = {
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
                    stringResource(if (isDefault) R.string.cleanup_default_state else R.string.cleanup_custom_state),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (promptFailed) {
                    // Explicit recovery UI for the async prompt job.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.cleanup_prompt_load_failed),
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
                        var overflows by remember(effective) { mutableStateOf(false) }
                        Text(
                            effective.ifEmpty { "…" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                        )
                        if (expanded || overflows) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.action_show_less else R.string.ux_read_prompt))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { editor = true }) {
                        Text(if (isDefault) stringResource(R.string.cleanup_customize) else stringResource(R.string.cleanup_edit))
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!isDefault) TextButton(
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
                                    stringResource(R.string.cleanup_dialog_note),
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
                val entries = entriesOf(lm, "entries")
                ReorderableEntries(
                    items = entryList(entries),
                    idOf = { it.optString("id") },
                    onMove = { from, to ->
                        moveEntry(entries, from, to); save(); version++
                    },
                ) { e, handle ->
                    val local = e.optString("kind") == "Local"
                    val id = e.optString("id")
                    val localStatus = if (!llmRunner) getString(localUnavailableRes()) else when (llmSelPack) {
                            LocalPackState.PRESENT -> getString(R.string.local_pack_present)
                            LocalPackState.MISSING -> getString(R.string.local_pack_missing)
                            LocalPackState.MISMATCH -> getString(R.string.local_pack_mismatch)
                            LocalPackState.INCOMPLETE -> getString(R.string.local_downloading, llmSelId ?: "")
                        }
                    ProviderEntryCard(
                        entry = e,
                        summary = if (local) getString(R.string.cleanup_local_summary) else "${e.optString("kind")} · ${e.optString("model")}",
                        onEdit = { if (local) localInfo = true else dialog = JSONObject(e.toString()) },
                        onToggle = { next ->
                            if (local) onLocalToggle(e, next, llmSelPack, { version++ }, llmRunner)
                            else { e.put("enabled", next); save(); version++ }
                        },
                        toggleEnabled = !local || llmRunner || e.optBoolean("enabled", true),
                        onDelete = {
                            val next = JSONArray()
                            for (j in 0 until entries.length()) {
                                if (entries.optJSONObject(j)?.optString("id") != id) next.put(entries.get(j))
                            }
                            lm.put("entries", next); save(); version++
                        },
                        handle = handle,
                        busy = if (local) llmTestBusy else llmBusy.contains(id),
                        result = if (local) llmTestResult else llmResults[id],
                        testEnabled = if (local) llmSelfTestAllowed(llmRunner, llmSelPack) else llmRemoteTestAllowed(e.optString("kind"), e.optString("url"), e.optString("model")),
                        onTest = { if (local) startLlmSelfTest() else startLlmRemoteTest(e) },
                        disabledReason = if (local) { if (llmRunner) localTestDisabledReason(true, llmSelPack == LocalPackState.PRESENT) else null } else llmTestDisabledReason(e.optString("kind"), e.optString("url"), e.optString("model")),
                        status = if (local) localStatus else null,
                        usingModel = if (local && llmRunner) getString(R.string.stt_using_model, llmSelId ?: "") else null,
                    )
                }
                if (entries.length() == 0) Text(stringResource(R.string.ux_provider_empty),
                    Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (!llmRunner) LocalUnavailableNote() else CollapsibleModelsSection(
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
                        stringResource(localUnavailableRes()),
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
                                        LocalModelDownload.RowState.CHECKING ->
                                            stringResource(R.string.local_checking)
                                        LocalModelDownload.RowState.DOWNLOADING,
                                        LocalModelDownload.RowState.INCOMPLETE,
                                        -> {
                                            // DOWNLOADING = live worker progress;
                                            // INCOMPLETE = interrupted file, resume
                                            // from verified bytes on disk.
                                            // Constraint-blocked (metered/offline,
                                            // zero bytes): say "waiting" instead of
                                            // a dead 0-byte bar — the unmetered
                                            // WorkManager constraint holds ENQUEUED
                                            // with no progress events at all.
                                            val waiting = if (row == LocalModelDownload.RowState.DOWNLOADING) {
                                                netState?.takeIf {
                                                    LocalModelDownload.needsWaiting(prog?.first, it)
                                                }
                                            } else {
                                                null
                                            }
                                            if (waiting != null) {
                                                stringResource(
                                                    if (waiting == LocalModelDownload.NetState.METERED) {
                                                        R.string.llm_wait_wifi
                                                    } else {
                                                        R.string.llm_wait_offline
                                                    },
                                                )
                                            } else {
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
                                    llmTaskFileFor(filesDir, a).absolutePath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Keep-warm policy (per-asset resident engine slot).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.keep_label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                KeepDropdown(
                                    LocalKeepWarm.readKeepSecs(
                                        statePrefs,
                                        LocalKeepWarm.KIND_LLM,
                                        a.id,
                                        LocalKeepWarm.LLM_DEFAULT,
                                    ),
                                ) { picked ->
                                    LocalKeepWarm.writeKeepSecs(
                                        statePrefs,
                                        LocalKeepWarm.KIND_LLM,
                                        a.id,
                                        picked,
                                    )
                                    if (picked == LocalKeepWarm.IMMEDIATE) {
                                        try {
                                            LocalLlmManager.dropCachedLlm(
                                                llmTaskFileFor(filesDir, a),
                                            )
                                        } catch (_: Exception) {
                                        }
                                    }
                                    version++
                                }
                            }
                            // Thinking switch (models with a thinking mode).
                            if (a.thinking) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.think_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    ThinkDropdown(
                                        LocalKeepWarm.readThinkBudget(statePrefs, a.id),
                                    ) { picked ->
                                        LocalKeepWarm.writeThinkBudget(statePrefs, a.id, picked)
                                        version++
                                    }
                                }
                            }
                            if (row == LocalModelDownload.RowState.DOWNLOADING ||
                                row == LocalModelDownload.RowState.INCOMPLETE ||
                                row == LocalModelDownload.RowState.CHECKING
                            ) {
                                Spacer(Modifier.height(4.dp))
                                val frac = if (row == LocalModelDownload.RowState.CHECKING) {
                                    null
                                } else if (row == LocalModelDownload.RowState.DOWNLOADING) {
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
                                    // No runtime: no Download offered (could never
                                    // run) — full-APK pointer instead.
                                    Text(
                                        stringResource(localUnavailableRes()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else when (row) {
                                    LocalModelDownload.RowState.DOWNLOADING -> {
                                        // Constraint-blocked with zero bytes:
                                        // offer explicit metered consent + a
                                        // visible Cancel (the row otherwise
                                        // has neither while stalled).
                                        val waiting = netState?.takeIf {
                                            LocalModelDownload.needsWaiting(prog?.first, it)
                                        }
                                        if (waiting == LocalModelDownload.NetState.METERED) {
                                            OutlinedButton(
                                                enabled = !noPin,
                                                onClick = { startLlmDownloadMetered(a) },
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.llm_use_metered,
                                                        (a.sizeBytes / 1_000_000).toString(),
                                                    ),
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        TextButton(onClick = {
                                            ModelDownloadManager.cancel(
                                                this@MainActivity,
                                                ModelDownloadSchedule.KIND_LLM,
                                                a.id,
                                            )
                                        }) {
                                            Text(stringResource(R.string.action_cancel))
                                        }
                                    }
                                    LocalModelDownload.RowState.CHECKING -> {}
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
                                        llmTaskFileFor(filesDir, doomed),
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
                                stringResource(localUnavailableRes()),
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
                                (llmSelected?.let { llmTaskFileFor(filesDir, it) }
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
        val urlMissing = kind == "Ollama" && url.isBlank()
        val modelMissing = !isLocalKind && model.isBlank()
        val keyMissing = enabled && cloudCredentialsMissing(kind, url, key)
        val canSave = !singletonBlocked && providerKindAvailable(kind, llmRunnerSupported()) && !urlMissing && !modelMissing && !keyMissing
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
            val ak = key
            workers.execute {
                val result = try {
                    probeLlmEndpoint(k, u, m, ak)
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
                        ChoiceSetting(getString(R.string.llm_kind), kind, availableProviderKinds(listOf("Ollama", "Cloud", "Local"), llmRunnerSupported())) {
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
                            stringResource(if (llmRunnerSupported()) R.string.llm_local_note else localUnavailableRes()),
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
                            isError = urlMissing,
                            supportingText = { if (urlMissing) Text(stringResource(R.string.provider_test_server_url_required)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it; draftResult = null },
                            label = { Text(stringResource(R.string.stt_model)) },
                            isError = modelMissing,
                            supportingText = { if (modelMissing) Text(stringResource(R.string.provider_test_model_required)) },
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == "Cloud") {
                        ApiKeyField(key, { key = it; draftResult = null },
                            isError = keyMissing, errorText = stringResource(R.string.task_api_key_required))
                    }
                    if (kind == "Ollama") {
                        ApiKeyField(key, { key = it; draftResult = null })
                        Text(
                            stringResource(R.string.stt_apikey_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (kind == "Ollama") {
                        OutlinedTextField(value = keepAlive, onValueChange = { keepAlive = it },
                            label = { Text(stringResource(R.string.llm_keepalive)) },
                            modifier = Modifier.fillMaxWidth())
                        ChoiceSetting(getString(R.string.llm_thinking), thinkLabels[thinking] ?: thinking, thinkLabels.values.toList()) { picked ->
                            thinking = thinkLabels.entries.firstOrNull { e -> e.value == picked }?.key ?: thinking
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stt_enabled), modifier = Modifier.weight(1f))
                        FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.stt_enabled) }, checked = enabled, onCheckedChange = { enabled = it })
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
                    enabled = canSave,
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
    private fun DictionaryScreen(s: JSONObject, onSync: () -> Unit) {
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
        val dictPager = androidx.compose.foundation.pager.rememberPagerState { 2 }
        val dictScope = androidx.compose.runtime.rememberCoroutineScope()
        val dictTab = dictPager.currentPage
        var next by rememberSaveable { mutableStateOf("") }
        var wordQuery by rememberSaveable { mutableStateOf("") }
        var snippetQuery by rememberSaveable { mutableStateOf("") }
        var dup by rememberSaveable { mutableStateOf(false) }
        fun addWord() {
            val w = next.trim()
            if (w.isBlank()) return
            if (words.any { it.equals(w, ignoreCase = true) }) {
                dup = true
                return
            }
            try {
                CoreBridge.addWord(w).getOrThrow()
                next = ""
            } catch (e: Exception) {
                toast(e.message ?: "failed")
            }
            reloadDict(); version++
            kickSync()
        }
        val fieldShape = MaterialTheme.shapes.extraLarge
        Column(modifier = Modifier.fillMaxSize()) {
            Centered { TabRow(selectedTabIndex = dictTab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = dictTab == 0, onClick = { dictScope.launch { dictPager.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.dict_tab_words) + " · ${words.size}") })
                Tab(selected = dictTab == 1, onClick = { dictScope.launch { dictPager.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.dict_tab_snippets) + " · ${snippets.size}") })
            } }
            // Store-backed (desktop app_dictionary parity): edits hit the
            // sync tables directly, so transcription and sync see them
            // without a restart.
            androidx.compose.foundation.pager.HorizontalPager(dictPager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
                Centered(Modifier.fillMaxHeight()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "sync") {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            DestinationRow(stringResource(R.string.ux_devices_sync), stringResource(R.string.dict_sync_hint), onSync)
                        }
                    }
                    if (page == 0) {
                        item(key = "wordsIntro") {
                            Text(stringResource(R.string.dict_words_sub), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item(key = "addWord") {
                            OutlinedTextField(
                                value = next, onValueChange = { next = it; dup = false },
                                placeholder = { Text(stringResource(R.string.dict_new_word)) },
                                singleLine = true,
                                shape = fieldShape,
                                isError = dup,
                                supportingText = if (dup) { { Text(stringResource(R.string.dict_dup)) } } else null,
                                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addWord() }),
                                trailingIcon = {
                                    IconButton(onClick = { addWord() }, enabled = next.isNotBlank()) {
                                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (words.size > 8) item(key = "searchWords") {
                            OutlinedTextField(
                                value = wordQuery, onValueChange = { wordQuery = it },
                                placeholder = { Text(stringResource(R.string.dict_search_words)) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                shape = fieldShape, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        val shown = words.filter { wordQuery.isBlank() || it.contains(wordQuery, ignoreCase = true) }
                        if (shown.isEmpty()) item(key = "wordsEmpty") {
                            Text(stringResource(if (words.isEmpty()) R.string.dict_no_words else R.string.dict_no_match),
                                Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(shown, key = { "w-$it" }) { w ->
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(w, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    IconButton(onClick = { pendingDeleteWord = w }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else {
                        item(key = "snippetsIntro") {
                            Text(stringResource(R.string.dict_snippets_sub), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item(key = "addSnippet") {
                            androidx.compose.material3.FilledTonalButton(
                                onClick = { snipOld = null; snipDialog = "" to "" },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.dict_add_snippet))
                            }
                        }
                        if (snippets.size > 5) item(key = "searchSnippets") {
                            OutlinedTextField(
                                value = snippetQuery, onValueChange = { snippetQuery = it },
                                placeholder = { Text(stringResource(R.string.dict_search_snippets)) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                shape = fieldShape, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        val shown = snippets.filter { (trig, repl) ->
                            snippetQuery.isBlank() || trig.contains(snippetQuery, ignoreCase = true) ||
                                repl.contains(snippetQuery, ignoreCase = true)
                        }
                        if (shown.isEmpty()) item(key = "snippetsEmpty") {
                            Text(stringResource(if (snippets.isEmpty()) R.string.dict_no_snippets else R.string.dict_no_match),
                                Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(shown, key = { (trig, repl) -> "s-$trig\u0000$repl" }) { (trig, repl) ->
                            Surface(
                                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                                onClick = { snipOld = trig; snipDialog = trig to repl },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.dict_snippet_say, trig), style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(2.dp))
                                        Text(repl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { pendingDeleteSnippet = trig }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
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
                                reloadDict(); version++
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
    private fun StatCard(label: String, value: String?, caption: String, modifier: Modifier = Modifier) {
        Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // null: still loading. A pulsing bar at the number's height, not a misleading 0.
                if (value == null) LoadingBar(Modifier.padding(vertical = 6.dp).width(56.dp).height(20.dp))
                else Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    /** Skeleton placeholder: a softly pulsing block where content is still loading. */
    @Composable
    private fun LoadingBar(modifier: Modifier = Modifier) {
        val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "loading").animateFloat(
            0.35f, 0.8f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "pulse",
        )
        Box(modifier.graphicsLayer { alpha = pulse }
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
    }

    /** Centered spinner for a section whose data has not arrived yet. */
    @Composable
    private fun LoadingBox(modifier: Modifier = Modifier) {
        Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }

    /** Heatmap shades by level: empty, then three steps of primary. */
    @Composable
    private fun heatShades(): List<Color> {
        val full = MaterialTheme.colorScheme.primary
        return listOf(MaterialTheme.colorScheme.surfaceVariant, full.copy(alpha = 0.35f), full.copy(alpha = 0.65f), full)
    }

    /** Less → More key; sits in the card header so it costs no extra height. */
    @Composable
    private fun HeatmapLegend() {
        val shades = heatShades()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.act_less), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            shades.forEach { shade ->
                Box(Modifier.size(10.dp).background(shade, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(3.dp))
            }
            Text(stringResource(R.string.act_more), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // Wider cards show more weeks instead of stretching the cells.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            HeatmapGrid(days, heatmapWeeks(maxWidth.value), onDayTap)
        }
    }

    @Composable
    private fun HeatmapGrid(days: List<Day>, weeks: Int, onDayTap: (Day) -> Unit) {
        val byDay = days.associate { (it.dayStart / 86400) to it }
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
        val shades = heatShades()
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
    }

    // -- History + privacy ----------------------------------------------

    private data class Hist(
        val uuid: String,
        val text: String,
        val rawText: String,
        val recordedAt: Long,
        val words: Int,
    )

    /**
     * Default tab: floating button quick toggle, last-dictation problems,
     * usage stats, then searchable history grouped by day. Reloads on every
     * resume / focus regain ([resumeTick]) instead of a refresh button.
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ActivityHome(s: JSONObject, onNavigate: (Int) -> Unit, onOpenProviders: (Int) -> Unit) {
        var stats by remember { mutableStateOf<JSONObject?>(null) }
        var days by remember { mutableStateOf<List<Day>>(emptyList()) }
        var statsErr by remember { mutableStateOf("") }
        var statsVersion by remember { mutableIntStateOf(0) }
        var items by remember { mutableStateOf(emptyList<Hist>()) }
        // False until the first history load lands: an empty list means "loading", not "no history".
        var histLoaded by remember { mutableStateOf(false) }
        var hasMore by remember { mutableStateOf(false) }
        var loadingMore by remember { mutableStateOf(false) }
        // Advances whenever a page load or reload ends, success or not (the fast scroller waits on it).
        var pagingDone by remember { mutableIntStateOf(0) }
        var confirmClear by remember { mutableStateOf(false) }
        var privOpen by rememberSaveable { mutableStateOf(false) }
        var expanded by rememberSaveable { mutableStateOf<String?>(null) }
        var histQuery by rememberSaveable { mutableStateOf("") }
        // Resume / focus regain, or a dictation saved while the app is open.
        val saved by OverlayService.historyChanged.collectAsState()
        val tick = resumeTick.intValue + saved
        LaunchedEffect(tick, statsVersion) {
            val gen = CoreBridge.SettingsJobs.nextJobGen(activityGen.get()).also { activityGen.set(it) }
            workers.execute {
                try {
                    val st = CoreBridge.usageStats().getOrThrow()
                    val arr = CoreBridge.dailyActivity(HISTORY_ALL_DAYS).getOrThrow()
                    val d = List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        Day(o.getLong("dayStart"), o.getInt("words"), o.getInt("utterances"))
                    }
                    runOnUiThread {
                        if (!jobApplies(activityGen.get(), gen)) return@runOnUiThread
                        stats = st
                        days = d
                        statsErr = ""
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(activityGen.get(), gen)) return@runOnUiThread
                        statsErr = e.message ?: "failed"
                    }
                }
            }
        }
        // Generation-aware history jobs (desktop reset parity): each op
        // runs off-thread, then reflects the committed store state; stale
        // generations drop, failures keep an explicit retryable error.
        fun histJob(work: () -> Unit, reset: Boolean = false, onOk: () -> Unit = {}, onFail: (String) -> Unit = {}) {
            val gen = CoreBridge.SettingsJobs.nextJobGen(histGen.get()).also { histGen.set(it) }
            histBusy.value = true
            // Reload what is already loaded (scroll position survives); a new search starts over.
            val want = if (reset) HISTORY_PAGE else historyReloadCount(items.size)
            val query = histQuery.trim()
            workers.execute {
                try {
                    work()
                    val committed = loadHistPage(want, null, query)
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        pagingDone++
                        items = committed
                        histLoaded = true
                        hasMore = committed.size == want
                        histError.value = ""
                        histBusy.value = false
                        onOk()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!jobApplies(histGen.get(), gen)) return@runOnUiThread
                        pagingDone++
                        histError.value = e.message ?: "failed"
                        histLoaded = true
                        histBusy.value = false
                        onFail(e.message ?: "failed")
                    }
                }
            }
        }
        /** False when refused: nothing loaded, no more rows, or paging busy. */
        fun loadMore(count: Int = HISTORY_PAGE): Boolean {
            val last = items.lastOrNull() ?: return false
            if (!hasMore || loadingMore || histBusy.value) return false
            loadingMore = true
            val gen = histGen.get()
            val query = histQuery.trim()
            workers.execute {
                val page = try { loadHistPage(count, last, query) } catch (_: Exception) { null }
                runOnUiThread {
                    loadingMore = false
                    pagingDone++
                    // A refresh or new search since then owns the list.
                    if (page == null || histGen.get() != gen) return@runOnUiThread
                    val known = items.mapTo(HashSet()) { it.uuid }
                    items = items + page.filter { it.uuid !in known }
                    hasMore = page.size == count
                }
            }
            return true
        }
        fun purgeHist(n: Int) {
            var purged = 0
            histJob({ purged = CoreBridge.purgeHistory(n).getOrThrow(); kickSync() },
                onOk = { statsVersion++; bgToast(resources.getQuantityString(R.plurals.hist_purged, purged, purged)) },
                onFail = { bgToast(getString(R.string.hist_purge_failed, it)) })
        }
        // The engine purges only on open and after a dictation, so History applies the
        // committed auto-delete setting itself (0 = keep). Read on the worker: after any queued save.
        fun applyRetention() = histJob({
            val days = CoreBridge.getSettings().getOrNull()?.optJSONObject("privacy")
                ?.optInt("transcript_auto_delete_days", 0) ?: 0
            if (days > 0 && CoreBridge.purgeHistory(days).getOrThrow() > 0) {
                kickSync()
                // Not onOk: a newer history job (search) would drop it, leaving stats stale.
                runOnUiThread { statsVersion++ }
            }
        })
        fun clearHist() = histJob({ CoreBridge.dataOf(CoreBridge.nativeClearHistory()); kickSync() },
            onOk = { statsVersion++; toast(getString(R.string.hist_cleared)) },
            onFail = { toast(getString(R.string.hist_clear_failed, it)) })
        fun deleteHist(uuid: String) = histJob({ CoreBridge.dataOf(CoreBridge.nativeDeleteHistory(uuid)); kickSync() },
            onOk = { statsVersion++; if (expanded == uuid) expanded = null },
            onFail = { toast(getString(R.string.hist_delete_failed, it)) })
        LaunchedEffect(tick) { applyRetention() }
        // Search runs in the database (all history, not just loaded pages), debounced.
        var searched by remember { mutableStateOf(histQuery) }
        LaunchedEffect(histQuery) {
            if (histQuery == searched) return@LaunchedEffect
            delay(250)
            searched = histQuery
            histJob({}, reset = true)
        }

        val statePrefs = getSharedPreferences("mw_state", MODE_PRIVATE)
        var issueKind by remember { mutableStateOf(statePrefs.getString(DictationIssue.PREF_KIND, null)) }
        var issueDetail by remember { mutableStateOf(statePrefs.getString(DictationIssue.PREF_DETAIL, null)) }
        androidx.compose.runtime.DisposableEffect(statePrefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == DictationIssue.PREF_KIND || key == DictationIssue.PREF_DETAIL) {
                    issueKind = p.getString(DictationIssue.PREF_KIND, null)
                    issueDetail = p.getString(DictationIssue.PREF_DETAIL, null)
                }
            }
            statePrefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { statePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // Hoisted out of the list: computed in a lazy item, the async pack check
        // restarts each time the card scrolls back in and its height jumps.
        // The cold-start hash check takes seconds: don't flash "no speech" meanwhile.
        val setup = bubbleSetupState(s, assumeWhileVerifying = true)
        val zone = remember { java.time.ZoneId.systemDefault() }
        val today = java.time.LocalDate.now(zone)
        val todayWord = getString(R.string.home_today)
        val yesterdayWord = getString(R.string.home_yesterday)
        val grouped = remember(items, today) {
            items.groupBy { historyDayLabel(it.recordedAt, today, zone, todayWord, yesterdayWord) }
        }
        val priv = s.optJSONObject("privacy") ?: JSONObject().also { s.put("privacy", it) }
        // Toggles write into [priv] before the committed reload lands; this re-reads them now.
        var privVersion by remember { mutableIntStateOf(0) }
        // Overview (button, problems, stats) and history: one list on narrow
        // windows, two independently scrolling panes on wide ones.
        val statsLoading = stats == null && statsErr.isEmpty()
        val overview: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            item(key = "float") { FloatingButtonQuick(s, setup, onNavigate, onOpenProviders) }
            val kind = issueKind
            if (kind != null) item(key = "issue") {
                val cleanup = kind == DictationIssue.CLEANUP || kind == DictationIssue.CLEANUP_SETUP
                val cause = DictationIssue.causeOf(issueDetail.orEmpty())
                DictationIssueCard(
                    title = getString(if (cleanup) R.string.issue_cleanup_title else R.string.issue_speech_title),
                    body = getString(if (kind == DictationIssue.CLEANUP_SETUP) R.string.issue_cleanup_setup_body
                        else if (cleanup) R.string.issue_cleanup_body else when (cause) {
                        DictationIssue.Cause.NO_PROVIDER -> R.string.issue_no_provider
                        DictationIssue.Cause.SIGN_IN -> R.string.issue_sign_in
                        DictationIssue.Cause.UNREACHABLE -> R.string.issue_unreachable
                        DictationIssue.Cause.MISSING_ADDRESS -> R.string.issue_missing_address
                        DictationIssue.Cause.MICROPHONE -> R.string.issue_microphone
                        DictationIssue.Cause.OTHER -> R.string.issue_other
                    }),
                    detail = issueDetail,
                    fixLabel = getString(if (!cleanup && cause == DictationIssue.Cause.MICROPHONE) R.string.issue_fix_mic
                        else if (cleanup) R.string.issue_fix_cleanup else R.string.issue_fix_speech),
                    onFix = {
                        if (!cleanup && cause == DictationIssue.Cause.MICROPHONE) openAppSettings()
                        else onOpenProviders(if (cleanup) 2 else 1)
                    },
                    onDismiss = { statePrefs.edit { remove(DictationIssue.PREF_KIND); remove(DictationIssue.PREF_DETAIL) } },
                )
            }
            item(key = "activityHeader") {
                Column(Modifier.padding(top = 16.dp, start = 4.dp)) {
                    Text(stringResource(R.string.tab_activity), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.act_sub), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "stats") {
                val u = stats?.optLong("dictations", 0L) ?: 0L
                val w = stats?.optLong("wordsSpoken", 0L) ?: 0L
                val wpm = stats?.optDouble("wordsPerMinute", 0.0) ?: 0.0
                val streak = stats?.optInt("streakDays", 0) ?: 0
                fun <T> shown(v: T): T? = if (statsLoading) null else v
                val cards: List<@Composable (Modifier) -> Unit> = listOf(
                    { m -> StatCard(stringResource(R.string.act_words), shown(compactWords(w)), stringResource(R.string.act_saved), m) },
                    { m -> StatCard(stringResource(R.string.act_wpm), shown(if (wpm > 0) "${wpm.toInt()}" else stringResource(R.string.act_wpm_none)),
                        stringResource(R.string.act_wpm_cap), m) },
                    { m -> StatCard(stringResource(R.string.act_dictations), shown("$u"), stringResource(R.string.act_saved), m) },
                    { m -> StatCard(stringResource(R.string.act_streak),
                        shown(if (streak == 1) stringResource(R.string.act_streak_one) else stringResource(R.string.act_streak_n, streak)),
                        stringResource(R.string.act_streak_cap), m) },
                )
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Four across when there is room, else two rows of two. Intrinsic
                    // height: cards in a row match the tallest one.
                    val perRow = if (maxWidth >= 560.dp) 4 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cards.chunked(perRow).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
                            }
                        }
                    }
                }
            }
            item(key = "heatmap") {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.act_chart), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (days.isNotEmpty()) HeatmapLegend()
                        }
                        Spacer(Modifier.height(8.dp))
                        if (statsErr.isNotEmpty()) {
                            Text(statsErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (statsLoading) {
                            LoadingBar(Modifier.fillMaxWidth().height(96.dp))
                        } else if (days.isEmpty()) {
                            Text(stringResource(R.string.act_empty), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        }
                    }
                }
            }
        }
        val history: androidx.compose.foundation.lazy.LazyListScope.(Boolean) -> Unit = { paned ->
            item(key = "historyHeader") {
                Row(Modifier.fillMaxWidth().padding(top = if (paned) 0.dp else 16.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tab_history), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.home_history_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Total from stats: the list only holds the pages loaded so far.
                    val total = stats?.optLong("dictations", -1L)?.takeIf { it >= 0 && histQuery.isBlank() }?.toInt()
                        ?: items.size.takeIf { histLoaded }
                    if (total != null) Text(resources.getQuantityString(R.plurals.hist_count, total, total), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "privacy") {
                CollapsibleCard(
                    title = stringResource(R.string.hist_privacy),
                    summary = stringResource(if (priv.optBoolean("history_enabled", true)) R.string.home_privacy_on else R.string.home_privacy_off),
                    open = privOpen, onToggle = { privOpen = !privOpen },
                ) {
                    @Suppress("UNUSED_EXPRESSION") privVersion
                    ToggleSetting(stringResource(R.string.hist_keep), stringResource(R.string.hist_keep_sub),
                        priv.optBoolean("history_enabled", true)) { priv.put("history_enabled", it); save(); privVersion++ }
                    ToggleSetting(stringResource(R.string.hist_cancelled), stringResource(R.string.hist_cancelled_sub),
                        priv.optBoolean("save_discarded_transcriptions", false)) { priv.put("save_discarded_transcriptions", it); save(); privVersion++ }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        var daysStr by remember { mutableStateOf(priv.optInt("transcript_auto_delete_days", 0).toString()) }
                        OutlinedTextField(
                            value = daysStr,
                            onValueChange = { daysStr = it.filter(Char::isDigit).take(4) },
                            label = { Text(stringResource(R.string.hist_days)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                priv.put("transcript_auto_delete_days", daysStr.toIntOrNull() ?: 0)
                                save()
                                // Queued behind the save on the same worker; applies whatever committed.
                                applyRetention()
                            }) { Text(stringResource(R.string.action_save)) }
                            TextButton(onClick = {
                                val n = daysStr.toIntOrNull() ?: 0
                                if (n > 0) purgeHist(n) else toast(getString(R.string.hist_set_days))
                            }) { Text(stringResource(R.string.hist_delete_older)) }
                            TextButton(
                                enabled = items.isNotEmpty() && !histBusy.value,
                                onClick = { confirmClear = true },
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) { Text(stringResource(R.string.home_clear_all)) }
                        }
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = histQuery, onValueChange = { histQuery = it },
                    placeholder = { Text(stringResource(R.string.hist_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (histQuery.isNotEmpty()) IconButton(onClick = { histQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (histError.value.isNotEmpty()) item(key = "histError") {
                // The next resume / focus regain retries the load.
                Text(histError.value, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (items.isEmpty()) item(key = "histEmpty") {
                // Skeleton rows shaped like history entries, pulsing like the stat cards.
                if (!histLoaded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { LoadingBar(Modifier.fillMaxWidth().height(64.dp)) }
                } else Text(
                    stringResource(if (histQuery.isBlank()) R.string.home_history_empty else R.string.dict_no_match),
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            grouped.forEach { (label, group) ->
                item(key = "day-$label") { HistoryDayHeader(label) }
                items(group, key = { it.uuid }) { h ->
                    HistoryEntryCard(
                        text = h.text,
                        original = h.rawText,
                        meta = historyTimeLabel(h.recordedAt, zone) + " · " + resources.getQuantityString(R.plurals.hist_words, h.words, h.words),
                        expanded = expanded == h.uuid,
                        busy = histBusy.value,
                        onCopy = { copyText(h.text, getString(R.string.home_copied)) },
                        onToggleExpanded = { expanded = if (expanded == h.uuid) null else h.uuid },
                        onCopyOriginal = { copyText(h.rawText, getString(R.string.home_copied_original)) },
                        onDelete = { deleteHist(h.uuid) },
                    )
                }
            }
            if (loadingMore) item(key = "loadingMore") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
        // Fast scroller rows: each day header plus its entries, in list order. The
        // history section starts after the overview items (single list only), then
        // historyHeader, privacy, search and the optional error / empty rows.
        val rowLabels = remember(grouped) { grouped.flatMap { (label, group) -> List(group.size + 1) { label } } }
        // Plus the unloaded rest from per-day counts, so the thumb holds still as pages
        // load. Search results have no per-day counts: those map loaded rows only.
        val scrollerRows = remember(rowLabels, days, hasMore, histQuery.isBlank(), today) {
            if (!hasMore || histQuery.isNotBlank()) rowLabels
            else historyScrollerRows(
                rowLabels,
                items.lastOrNull()?.let { java.time.Instant.ofEpochSecond(it.recordedAt).atZone(zone).toLocalDate() },
                grouped.values.lastOrNull()?.size ?: 0,
                days.map { java.time.LocalDate.ofEpochDay(it.dayStart / 86400) to it.utterances },
            ) { historyDayLabel(it, today, todayWord, yesterdayWord) }
        }
        // Whole pages through the entry at [row] (day headers are not entries).
        val loadThrough: (Int) -> Int? = { row ->
            // Live counter, not the composed one: a page may have ended since this frame.
            if (loadMore((historyReloadCount(historyEntriesThrough(scrollerRows, row)) - items.size).coerceAtLeast(HISTORY_PAGE))) pagingDone else null
        }
        val historyLead = 3 + (if (histError.value.isNotEmpty()) 1 else 0) + (if (items.isEmpty()) 1 else 0)
        val overviewCount = 4 + (if (issueKind != null) 1 else 0)
        val scrollerPadding = Modifier.padding(top = 8.dp, bottom = 72.dp)
        val listPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
        val singleState = androidx.compose.foundation.lazy.rememberLazyListState()
        val overviewState = androidx.compose.foundation.lazy.rememberLazyListState()
        val historyState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Wires one list that holds history: loads the next page near the end, and
        // reports when the history header has scrolled off the top (back-to-top button).
        @Composable
        fun historyList(state: androidx.compose.foundation.lazy.LazyListState): Boolean {
            LaunchedEffect(state) {
                // Emits the item count while near the end, so each grown list asks again.
                androidx.compose.runtime.snapshotFlow {
                    val info = state.layoutInfo
                    val nearEnd = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 15
                    if (nearEnd) info.totalItemsCount else -1
                }.collect { if (it >= 0) loadMore() }
            }
            val pastHeader by remember(state) {
                androidx.compose.runtime.derivedStateOf {
                    val visible = state.layoutInfo.visibleItemsInfo
                    visible.isNotEmpty() && visible.none { it.key in ACTIVITY_TOP_KEYS }
                }
            }
            return pastHeader
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= TWO_PANE_MIN_WIDTH) {
                val showTop = historyList(historyState)
                LaunchedEffect(Unit) {
                    activityScrollTop.collect {
                        launch { overviewState.scrollToTopTimed() }
                        launch { historyState.scrollToTopTimed() }
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.weight(0.42f).fillMaxHeight(), state = overviewState, contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { overview() }
                    Box(Modifier.weight(0.58f).fillMaxHeight()) {
                        LazyColumn(Modifier.fillMaxSize(), state = historyState, contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) { history(true) }
                        // Keyed by query: a new search resets a pending drop and the drag handler with it.
                        androidx.compose.runtime.key(histQuery.trim()) {
                            HistoryFastScroller(historyState, historyLead, scrollerRows, rowLabels.size, hasMore, loadThrough, pagingDone, showTop,
                                Modifier.align(Alignment.TopEnd).then(scrollerPadding))
                        }
                        BackToTopButton(showTop, { scope.launch { historyState.scrollToTopTimed() } },
                            Modifier.align(Alignment.BottomEnd).padding(16.dp))
                    }
                }
            } else {
                val showTop = historyList(singleState)
                LaunchedEffect(Unit) { activityScrollTop.collect { launch { singleState.scrollToTopTimed() } } }
                LazyColumn(Modifier.fillMaxSize(), state = singleState, contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    overview()
                    history(false)
                }
                androidx.compose.runtime.key(histQuery.trim()) {
                    HistoryFastScroller(singleState, overviewCount + historyLead, scrollerRows, rowLabels.size, hasMore, loadThrough, pagingDone, showTop,
                        Modifier.align(Alignment.TopEnd).then(scrollerPadding))
                }
                BackToTopButton(showTop, { scope.launch { singleState.scrollToTopTimed() } },
                    Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.home_clear_title)) },
                text = { Text(stringResource(R.string.home_clear_body)) },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; clearHist() }) { Text(stringResource(R.string.action_clear)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }

    /** One history page (throws, so jobs keep a retryable error); [before] = last row already shown. */
    private fun loadHistPage(limit: Int, before: Hist?, query: String): List<Hist> {
        val raw = CoreBridge.nativeGetHistoryPage(limit, before?.recordedAt ?: 0L, before?.uuid.orEmpty(), query)
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Hist(
                o.optString("uuid"),
                o.optString("text"),
                o.optString("rawText"),
                o.optLong("recordedAt"),
                o.optInt("wordCount"),
            )
        }
    }

    // -- Sync -----------------------------------------------------------

    private fun agoText(epochSecs: Long): String {
        if (epochSecs <= 0) return getString(R.string.sync_ago_never)
        val dt = System.currentTimeMillis() / 1000 - epochSecs
        return when {
            dt < 60 -> getString(R.string.sync_ago_now)
            dt < 3600 -> getString(R.string.sync_ago_min, dt / 60)
            dt < 86400 -> getString(R.string.sync_ago_h, dt / 3600)
            else -> getString(R.string.sync_ago_d, dt / 86400)
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
                        pendingCopy ?: stringResource(R.string.sync_stopped)
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
                            stringResource(R.string.sync_advertise_sub),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.sync_advertise) }, checked = adv, onCheckedChange = {
                        sync.put("advertise", it); save(); version++
                        restartSyncIfRunning(running)
                    })
                }
                val enabled = sync.optBoolean("enabled", true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sync_enabled), modifier = Modifier.weight(1f))
                    FeedbackSwitch(modifier = Modifier.semantics { contentDescription = getString(R.string.sync_enabled) }, checked = enabled, onCheckedChange = {
                        sync.put("enabled", it); save(); version++
                        if (!it && running) {
                            SyncService.stop(this@MainActivity)
                            CoreBridge.syncStop()
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
                            stringResource(R.string.sync_boot_sub),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FeedbackSwitch(
                        modifier = Modifier.semantics { contentDescription = getString(R.string.sync_boot) },
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
                            // Start is the opt-in: core refuses to start
                            // without an explicitly stored enabled flag,
                            // which a fresh install does not have yet.
                            sync.put("enabled", true)
                            version++
                            // Single-owner start: the service owns
                            // syncStart (sync_start() begins with
                            // sync_stop(), so a concurrent activity-side
                            // syncStart() kills the service's manager and
                            // the first tap nets dead sync). Never call
                            // CoreBridge.syncStart() here; just refresh
                            // status once the service promotes.
                            save { SyncService.start(this@MainActivity) }
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
                            refresh()
                        }) { Text(stringResource(R.string.action_stop)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = running && busy.isEmpty(),
                        onClick = {
                            busy = getString(R.string.sync_syncing)
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
                    ) { Text(busy.ifEmpty { stringResource(R.string.action_sync_now) }) }
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
                        lastSync = getString(R.string.sync_lastsync, agoText(p.optLong("lastSync", 0))),
                        onSync = {
                            busy = getString(R.string.sync_syncing)
                            workers.execute {
                                try {
                                    val msg = CoreBridge.syncNow(id).getOrThrow()
                                    runOnUiThread { note(id, msg) }
                                } catch (e: Exception) {
                                    runOnUiThread { note(id, getString(R.string.sync_failed, e.message)) }
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
            Section(stringResource(R.string.sync_waiting_title, waiting.size)) {
                if (waiting.isNotEmpty()) Text(
                    stringResource(R.string.sync_waiting_sub),
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
                    stringResource(R.string.sync_nearby_sub),
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
            Section(stringResource(R.string.sync_add_title)) {
                Text(
                    stringResource(R.string.sync_add_sub),
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
                                runOnUiThread { notes = notes + ("__add" to getString(R.string.sync_add_failed, e.message)) }
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
                            (addrs + disc).distinct().take(2).joinToString(" · ").ifEmpty { getString(R.string.sync_noaddr) },
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
