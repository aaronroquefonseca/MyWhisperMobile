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
         * [existing].
         */
        fun spliceAtCursor(existing: String, selStart: Int, selEnd: Int, insert: String): String {
            if (insert.isEmpty()) return existing
            val n = existing.length
            if (selStart < 0 || selEnd < 0 || selStart > selEnd || selStart > n || selEnd > n) {
                return existing + insert
            }
            return existing.substring(0, selStart) + insert + existing.substring(selEnd)
        }

        /**
         * Splice [text] into the currently focused editable field at
         * the cursor (replacing any selected range).
         * Returns false when there is no service, no focus, or the
         * field is not editable (caller should surface a hint).
         */
        fun commit(text: String): Boolean {
            val s = live ?: return false
            return try {
                val root = s.rootInActiveWindow ?: return false
                val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return false
                if (!node.isEditable) return false
                val existing = node.text?.toString() ?: ""
                val merged = spliceAtCursor(
                    existing,
                    node.textSelectionStart,
                    node.textSelectionEnd,
                    text,
                )
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        merged,
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
