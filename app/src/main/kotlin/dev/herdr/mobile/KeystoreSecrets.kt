package dev.herdr.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed secret storage. One AES-256-GCM key lives in the Android Keystore under
 * [KEY_ALIAS]; values are AES-encrypted and only the ciphertext touches SharedPreferences.
 *
 * Nothing here ever writes key material, tokens, or passphrases in the clear. Each value
 * gets a fresh 12-byte IV; aliases are caller-chosen (`ssh-key-<uuid>`, `token-<uuid>`).
 */
class KeystoreSecrets(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "herdr_secrets"
        private const val KEY_ALIAS = "herdr-mobile-secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(alias: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString("$alias.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            putString("$alias.data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    fun get(alias: String): String? {
        val iv = prefs.getString("$alias.iv", null) ?: return null
        val data = prefs.getString("$alias.data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun delete(alias: String) {
        prefs.edit {
            remove("$alias.iv")
            remove("$alias.data")
        }
    }
}
