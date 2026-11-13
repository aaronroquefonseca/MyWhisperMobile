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
 * Text commit path for the floating bubble (which has no
 * InputConnection): pastes dictation results into the focused
 * editable field via the clipboard + ACTION_PASTE, mirroring the
 * desktop flow. The APP inserts at its own cursor — we never
 * read-replace the whole field, so duplicating its text is
 * impossible by construction (the SET_TEXT-splice approach is gone).
 *
 * History-leak guard: on API 33+ the clip carries
 * ClipDescription.EXTRA_IS_SENSITIVE, excluding the transcription
 * from the keyboard clipboard history/suggestions; below 33 the
 * flag does not exist and commit proceeds without it.
 *
 * Virtual-input-device alternative (VirtualDeviceManager +
 * companion-device association) was rejected: it needs
 * CREATE_VIRTUAL_DEVICE + a virtual display/session for key
 * injection, while the accessibility service already holds a direct
 * paste path with no extra privilege.
 */
class MyWhisperAccessibility : AccessibilityService() {

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
            val focused = try {
                live?.rootInActiveWindow
                    ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.isEditable == true
            } catch (_: Exception) {
                false
            }
            if (focused != FloatState.hasTextFocus) {
                FloatState.hasTextFocus = focused
                FloatState.refresh()
            }
        }

        /**
         * True on API 33+, where ClipDescription.EXTRA_IS_SENSITIVE
         * exists and keeps the transcription out of the keyboard
         * clipboard history/suggestions. Pure (sdkInt param) for JVM
         * tests; production passes Build.VERSION.SDK_INT.
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
         * persist in keyboard history below API 33.
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

        /**
         * Paste [text] into the currently focused editable field at
         * the app's own cursor via clipboard + ACTION_PASTE.
         * Returns false when there is no service, no focus, the field
         * is not editable, or the paste fails (caller surfaces a hint).
         *
         * Bubble-commit trace (repro aid): logs the exact field bytes
         * before, the exact payload pasted, and the exact bytes after,
         * so a logcat capture discriminates deliver-twice vs
         * app-side echo. Logcat is on-device local; the repro uses
         * synthetic reference text.
         */
        // InlinedApi: EXTRA_IS_SENSITIVE is API 33+ (min 29) — safe
        // because shouldMarkSensitive gates on SDK_INT >= 33 first.
        @android.annotation.SuppressLint("InlinedApi")
        fun commit(text: String): Boolean {
            val s = live ?: run {
                android.util.Log.i("MwCommit", "commit no-service insertLen=${text.length}")
                return false
            }
            if (text.isEmpty()) {
                android.util.Log.i("MwCommit", "commit drop-empty")
                return true
            }
            return try {
                val root = s.rootInActiveWindow ?: run {
                    android.util.Log.i("MwCommit", "commit no-root insertLen=${text.length}")
                    return false
                }
                val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
                    android.util.Log.i("MwCommit", "commit no-focus insertLen=${text.length}")
                    return false
                }
                if (!node.isEditable) {
                    android.util.Log.i("MwCommit", "commit not-editable")
                    return false
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
                        "before=<$existing> insert=<$text> payload=<$payload> hint=<$hint>",
                )
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
                    return false
                }
                val after = try {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text?.toString()
                } catch (_: Exception) {
                    null
                }
                android.util.Log.i(
                    "MwCommit",
                    "commit result ok=$ok afterLen=${after?.length} " +
                        "after=<$after>",
                )
                ok
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        live = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (live === this) live = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Drive the floating bubble: visible only while a text
        // field is focused (and our IME is not the active one —
        // that half lives in the IME via FloatState).
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> syncFocus()
            else -> Unit
        }
    }


    override fun onInterrupt() {
    }
}
