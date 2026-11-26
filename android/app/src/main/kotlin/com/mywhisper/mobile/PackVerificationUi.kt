package com.mywhisper.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Composition-scoped, fail-closed verification for the selected onboarding pack. */
@Composable
internal fun <T : Any> rememberVerifiedPack(
    id: String,
    fingerprint: String?,
    changes: Int,
    cache: LocalModelDownload.PackVerifyCache,
    currentFingerprint: () -> String?,
    stillSelected: () -> Boolean,
    isVerified: (T) -> Boolean,
    // Returned until the first check of [fingerprint] finishes.
    whilePending: Boolean = false,
    verify: () -> T,
): Boolean {
    var verifiedFingerprint by remember(id, changes) { mutableStateOf<String?>(null) }
    var checkedFingerprint by remember(id, changes) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, fingerprint, changes) {
        verifiedFingerprint = null
        if (fingerprint == null) return@LaunchedEffect
        val verified = runInterruptible(Dispatchers.IO) {
            try {
                cache.getStable(id, fingerprint, currentFingerprint, stillSelected, verify)?.let(isVerified) == true
            } catch (_: Exception) {
                false
            }
        }
        if (verified && currentFingerprint() == fingerprint && stillSelected()) {
            verifiedFingerprint = fingerprint
        }
        checkedFingerprint = fingerprint
    }
    if (fingerprint != null && checkedFingerprint != fingerprint) return whilePending
    return fingerprint != null && verifiedFingerprint == fingerprint
}
