package org.heyogesh.drive.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the bearer session encrypted with a device-bound Android Keystore key. */
data class StoredSession(val token: String, val expiresAtMillis: Long)

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("heyogesh_drive_session", Context.MODE_PRIVATE)

    fun save(token: String, expiresAtMillis: Long) {
        val encrypted = encrypt("$token\n$expiresAtMillis")
        preferences.edit().putString(KEY_SESSION, encrypted).apply()
    }

    fun getValid(): StoredSession? {
        val value = preferences.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val parts = decrypt(value).split('\n')
            val session = StoredSession(parts[0], parts[1].toLong())
            session.takeIf { it.expiresAtMillis > System.currentTimeMillis() + 15_000 }
        }.getOrNull().also { if (it == null) clear() }
    }

    fun clear() = preferences.edit().remove(KEY_SESSION).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val pieces = value.split(':', limit = 2)
        require(pieces.size == 2)
        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    }

    private companion object {
        const val KEY_SESSION = "encrypted_session"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "heyogesh_drive_session_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
