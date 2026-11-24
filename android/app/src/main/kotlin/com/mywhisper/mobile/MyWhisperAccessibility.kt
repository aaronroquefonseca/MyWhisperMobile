package com.mywhisper.mobile

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bubble text insertion uses the accessibility input connection on Android 13+.
 * Older devices and unavailable connections retain clipboard paste compatibility.
 * Sensitive clipboard metadata hides previews; it cannot guarantee history exclusion.
 */
class MyWhisperAccessibility : AccessibilityService() {

    /** Delivery outcome; MOVED means focus left the editor bound at record start. */
    enum class Commit { OK, FAILED, MOVED }

    companion object {
        @Volatile var live: MyWhisperAccessibility? = null
            private set

        fun isOn(ctx: android.content.Context): Boolean {
            val expected = "${ctx.packageName}/${MyWhisperAccessibility::class.java.name}"
            return try {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )?.contains(expected) == true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Resolve input focus from the live window instead of trusting
         * one event's source: IME windows emit their own focus events
         * (their source is not editable) and could clear a real
         * text-field focus, leaving the bubble hidden while the user
         * types (owner repro on a launcher search field). One query,
         * both directions, no flapping.
         */
        fun syncFocus() {
            val node = try {
                live?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Exception) { null }
            val search = try {
                node?.let { BubbleAppearance.isSearch(it.inputType, it.viewIdResourceName, it.hintText?.toString()) } == true
            } catch (_: Exception) { false }
            val focused = try {
                node?.isEditable == true
            } catch (_: Exception) {
                false
            }
            val keyboard = try {
                live?.windows?.any { window ->
                    val bounds = android.graphics.Rect()
                    window.getBoundsInScreen(bounds)
                    window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && !bounds.isEmpty
                } == true
            } catch (_: Exception) { false }
            if (focused != FloatState.hasTextFocus || keyboard != FloatState.keyboardVisible || search != FloatState.searchField) {
                FloatState.searchField = search
                FloatState.hasTextFocus = focused
                FloatState.keyboardVisible = keyboard
                FloatState.refresh()
            }
        }

        /**
         * API gate for sensitive clipboard preview metadata.
         * This metadata does not guarantee exclusion from clipboard history.
         */
        fun shouldMarkSensitive(sdkInt: Int): Boolean = sdkInt >= 33

        /**
         * Hint-leak guard: on some fields (older phones / WebViews /
         * OEM inputs) the placeholder leaks into `node.text` when the
         * field is actually empty, so pasting would materialize it in
         * front of the dictation ("Search…" + "hello"). When the
         * exposed text exactly equals the node's hint, the field is
         * empty — treat it as such (bare insert, no join space).
         * Pure for JVM tests.
         */
        fun withoutHint(existing: String, hint: String?): String =
            if (hint != null && hint.isNotEmpty() && existing == hint) "" else existing

        /**
         * Pure paste payload: the exact bytes to put on the clipboard
         * for ACTION_PASTE. End-appends (cursor at the end, or a
         * stale/invalid selection — e.g. node recycled between read
         * and paste) join with at most one leading space, only between
         * two word chars ("REF"+"X" -> " X", so the app yields
         * "REF X"); already-spaced sides never double up, punctuation
         * pastes directly. Mid-text inserts and selection replaces
         * stay byte-exact (no leading space: the cursor context, not
         * the field tail, owns the join). Empty field or empty insert
         * passes through untouched.
         */
        fun pasteInsert(existing: String, selStart: Int, selEnd: Int, insert: String): String {
            if (insert.isEmpty() || existing.isEmpty()) return insert
            val n = existing.length
            val atEnd = selStart == n && selEnd == n ||
                selStart < 0 || selEnd < 0 || selStart > selEnd || selStart > n || selEnd > n
            if (!atEnd) return insert
            if (existing.last().isWhitespace() || insert.first().isWhitespace()) return insert
            if (existing.last().isLetterOrDigit() && insert.first().isLetterOrDigit()) {
                return " $insert"
            }
            return insert
        }

        /**
         * Clipboard round-trip for the paste commit: capture the
         * user's prior clip, publish the transcription, paste, then
         * restore the prior clip (when it holds items) or clear the
         * clipboard (nothing to restore — clearPrimaryClip is API 28+,
         * minSdk 29 is safe). The transcription must never survive as
         * the live clip: it would overwrite the user's clipboard and
         * remain available to other clipboard consumers. History consumers may
         * already have captured it; restoration cannot remove their entries.
         *
         * Exceptions route to a clear fallback so a mid-round-trip
         * failure cannot leak the transcription — except when publish
         * itself threw (nothing was overwritten, so the user's clip is
         * untouched and clearing would destroy it). Generic over the
         * clip handle so JVM tests drive it with fakes.
         */
        internal fun <C> withTranscriptionClip(
            capturePrior: () -> C?,
            priorCount: (C) -> Int,
            publish: () -> Unit,
            doPaste: () -> Boolean,
            restore: (C) -> Unit,
            clear: () -> Unit,
        ): Boolean {
            var overwrote = false
            try {
                val prior = try {
                    capturePrior()
                } catch (_: Exception) {
                    null
                }
                publish()
                overwrote = true
                val ok = doPaste()
                try {
                    if (prior != null && priorCount(prior) > 0) restore(prior) else clear()
                } catch (_: Exception) {
                }
                return ok
            } catch (_: Exception) {
                if (overwrote) {
                    try {
                        clear()
                    } catch (_: Exception) {
                    }
                }
                return false
            }
        }

        /** The editable field holding input focus now; bound at record start as the utterance's target. */
        fun focusedEditor(): AccessibilityNodeInfo? = try {
            val s = live
            // A stale cache can name the previous field; drop it and ask again (the cache is API 33+).
            liveFocus(s) ?: if (Build.VERSION.SDK_INT >= 33 && s?.clearCache() == true) liveFocus(s) else null
        } catch (_: Exception) {
            null
        }

        // findFocus can answer from the API 33+ accessibility cache; refresh() re-reads the live node.
        private fun liveFocus(s: MyWhisperAccessibility?): AccessibilityNodeInfo? =
            s?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.refresh() && it.isFocused && it.isEditable }

        /**
         * Insert [text] at the app's own cursor in [bound], which must
         * still hold input focus. Returns MOVED when focus left it or
         * no editor has focus now (nothing inserted, no clip published),
         * and FAILED when there is no service, nothing was bound at
         * record start, or the insert fails.
         *
         * Diagnostics record lengths and delivery path only, never field or transcript text.
         */
        // InlinedApi: EXTRA_IS_SENSITIVE is API 33+ (min 29) — safe
        // because shouldMarkSensitive gates on SDK_INT >= 33 first.
        @android.annotation.SuppressLint("InlinedApi")
        fun commit(text: String, bound: AccessibilityNodeInfo?): Commit {
            val s = live ?: run {
                android.util.Log.i("MwCommit", "commit no-service insertLen=${text.length}")
                return Commit.FAILED
            }
            if (text.isEmpty()) {
                android.util.Log.i("MwCommit", "commit drop-empty")
                return Commit.OK
            }
            if (bound == null) {
                android.util.Log.i("MwCommit", "commit unbound insertLen=${text.length}")
                return Commit.FAILED
            }
            return try {
                // Node equality is window id plus source node id: any other field, in this app or another, differs.
                val node = focusedEditor()
                if (node == null || node != bound) {
                    android.util.Log.i("MwCommit", "commit moved focused=${node != null}")
                    return Commit.MOVED
                }
                val rawExisting = node.text?.toString() ?: ""
                // Hint text is API 26+; minSdk 29 makes this a direct
                // read (best-effort: a recycled node may throw).
                val hint = try {
                    node.hintText?.toString()
                } catch (_: Exception) {
                    null
                }
                val existing = withoutHint(rawExisting, hint)
                val selS = node.textSelectionStart
                val selE = node.textSelectionEnd
                val payload = pasteInsert(existing, selS, selE, text)
                android.util.Log.i(
                    "MwCommit",
                    "commit before len=${existing.length} sel=($selS,$selE) " +
                        "insertLen=${text.length} payloadLen=${payload.length} " +
                        "path=resolve",
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    val input = s.inputMethod
                    val connection = input?.currentInputConnection
                    val editor = input?.currentInputEditorInfo
                    // A live connection inserts at the app's cursor without publishing a clip.
                    // Never retry after dispatch: commitText has no success acknowledgment,
                    // and a paste retry could duplicate the utterance.
                    if (input?.currentInputStarted == true && connection != null &&
                        editor?.packageName == node.packageName?.toString()
                    ) {
                        connection.commitText(payload, 1, null)
                        android.util.Log.i("MwCommit", "commit direct insertLen=${payload.length}")
                        return Commit.OK
                    }
                }
                val ok = try {
                    val cm = s.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("mw-transcription", payload)
                    if (shouldMarkSensitive(Build.VERSION.SDK_INT)) {
                        val xb = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                        clip.description.setExtras(xb)
                    }
                    withTranscriptionClip(
                        capturePrior = { cm.primaryClip },
                        priorCount = { it.itemCount },
                        publish = { cm.setPrimaryClip(clip) },
                        doPaste = { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) },
                        restore = { prior -> cm.setPrimaryClip(prior) },
                        clear = { cm.clearPrimaryClip() },
                    )
                } catch (e: Exception) {
                    android.util.Log.i("MwCommit", "commit clip-fail: ${e.message}")
                    return Commit.FAILED
                }
                val after = try {
                    if (node.refresh()) node.text?.toString() else null
                } catch (_: Exception) {
                    null
                }
                android.util.Log.i(
                    "MwCommit",
                    "commit result ok=$ok afterLen=${after?.length} " +
                        "path=clipboard",
                )
                if (ok) Commit.OK else Commit.FAILED
            } catch (_: Exception) {
                Commit.FAILED
            }
        }
    }

    private val focusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val contentFocusCheck = Runnable { syncFocus() }

    override fun onServiceConnected() {
        live = this
        syncFocus()
        // The system reconnects accessibility after boot/process recovery. Restore
        // enabled readiness even if the boot receiver ran before this binding.
        try {
            val enabled = CoreBridge.getSettings().getOrThrow().optJSONObject("preferences")
                ?.optBoolean("float_enabled", true) == true
            if (enabled) OverlayService.start(this)
        } catch (e: Exception) {
            android.util.Log.i("MwFloat", "accessibility restore deferred: ${e.message}")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        focusHandler.removeCallbacks(contentFocusCheck)
        if (live === this) {
            live = null
            FloatState.searchField = false
            FloatState.keyboardVisible = false
            FloatState.hasTextFocus = false
            FloatState.refresh()
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Drive the floating bubble: visible only while a text
        // field is focused (and our IME is not the active one —
        // that half lives in the IME via FloatState).
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                focusHandler.removeCallbacks(contentFocusCheck)
                focusHandler.postDelayed(contentFocusCheck, 32L)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            -> syncFocus()
            else -> Unit
        }
    }


    override fun onInterrupt() {
    }
}
