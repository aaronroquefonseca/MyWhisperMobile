package com.mywhisper.mobile

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Text commit path for the floating bubble (which has no
 * InputConnection): appends dictation results to the focused
 * editable field. The IME path is untouched.
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
         * Pure cursor splice: replace [selStart, selEnd) in [existing]
         * with [insert]. Stale/invalid selection (negative, reversed,
         * or past the end — e.g. node recycled between read and
         * ACTION_SET_TEXT) falls back to appending, never duplicating
         * [existing]. A true end-append (cursor at the end, or the
         * stale-selection fallback) joins with one space when a word
         * char meets a word char ("REF"+"X" -> "REF X"); mid-text
         * inserts and selection replaces stay byte-exact.
         */
        fun spliceAtCursor(existing: String, selStart: Int, selEnd: Int, insert: String): String {
            if (insert.isEmpty()) return existing
            val n = existing.length
            if (selStart < 0 || selEnd < 0 || selStart > selEnd || selStart > n || selEnd > n) {
                return appendWord(existing, insert)
            }
            if (selStart == n && selEnd == n) {
                return appendWord(existing, insert)
            }
            return existing.substring(0, selStart) + insert + existing.substring(selEnd)
        }

        /**
         * Word-append join: at most one separating space, only between
         * two word chars. Punctuation appends directly ("hello"+"," =
         * "hello,"); already-spaced sides never double up.
         */
        private fun appendWord(existing: String, insert: String): String {
            if (existing.isEmpty() || insert.isEmpty()) return existing + insert
            val leftSpaced = existing.last().isWhitespace()
            val rightSpaced = insert.first().isWhitespace()
            if (leftSpaced || rightSpaced) return existing + insert
            if (existing.last().isLetterOrDigit() && insert.first().isLetterOrDigit()) {
                return "$existing $insert"
            }
            return existing + insert
        }

        /**
         * Splice [text] into the currently focused editable field at
         * the cursor (replacing any selected range).
         * Returns false when there is no service, no focus, or the
         * field is not editable (caller should surface a hint).
         *
         * Bubble-commit trace (repro aid): logs the exact field bytes
         * before, the exact bytes sent, and the exact bytes after, so a
         * logcat capture discriminates deliver-twice vs stale-read vs
         * app-side echo. Logcat is on-device local; the repro uses
         * synthetic reference text.
         */
        fun commit(text: String): Boolean {
            val s = live ?: run {
                android.util.Log.i("MwCommit", "commit no-service insertLen=${text.length}")
                return false
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
                val existing = node.text?.toString() ?: ""
                val selS = node.textSelectionStart
                val selE = node.textSelectionEnd
                android.util.Log.i(
                    "MwCommit",
                    "commit before len=${existing.length} sel=($selS,$selE) " +
                        "insertLen=${text.length} before=<$existing> insert=<$text>",
                )
                val merged = spliceAtCursor(existing, selS, selE, text)
                android.util.Log.i(
                    "MwCommit",
                    "commit merged len=${merged.length} merged=<$merged>",
                )
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        merged,
                    )
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                val after = try {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text?.toString()
                } catch (_: Exception) {
                    null
                }
                android.util.Log.i(
                    "MwCommit",
                    "commit result ok=$ok afterLen=${after?.length} " +
                        "match=${after == merged} after=<$after>",
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
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                try {
                    FloatState.hasTextFocus = e.source?.isEditable == true
                } catch (_: Exception) {
                    FloatState.hasTextFocus = false
                }
                FloatState.refresh()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                FloatState.hasTextFocus = false
                FloatState.refresh()
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
    }
}
