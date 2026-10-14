package com.mywhisper.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * SQLCipher key for `state.db`, Android equivalent of the desktop
 * Secret Service `mywhisper/state-db` entry: a random 32-byte key kept
 * in Keystore-backed EncryptedSharedPreferences, handed to the core as
 * 64 hex chars via `nativeOpen`.
 */
object KeystoreKey {
    private const val FILE = "mw_keys"
    private const val KEY = "db_key"

    private fun prefs(ctx: Context): SharedPreferences {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    internal fun isValidHexKey(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun getOrCreateHex(ctx: Context): String {
        val p = prefs(ctx)
        // Fail closed: length-only check would hand a non-hex string to
        // nativeOpen/SQLCipher. Malformed values are dropped and replaced.
        p.getString(KEY, null)?.let { if (isValidHexKey(it)) return it }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = raw.hex()
        p.edit().putString(KEY, hex).apply()
        return hex
    }
}
