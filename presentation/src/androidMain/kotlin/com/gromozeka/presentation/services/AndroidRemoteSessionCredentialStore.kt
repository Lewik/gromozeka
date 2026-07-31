package com.gromozeka.presentation.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gromozeka.client.RemoteSessionCredentialStore
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidRemoteSessionCredentialStore(
    context: Context,
) : RemoteSessionCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(serverKey: String): String? {
        val preferenceKey = preferenceKey(serverKey)
        val stored = preferences.getString(preferenceKey, null) ?: return null
        return runCatching { decrypt(serverKey, stored) }
            .getOrElse {
                preferences.edit().remove(preferenceKey).commit()
                null
            }
    }

    @Synchronized
    override fun save(serverKey: String, encodedSession: String?) {
        val preferenceKey = preferenceKey(serverKey)
        val editor = preferences.edit()
        if (encodedSession == null) {
            editor.remove(preferenceKey)
        } else {
            editor.putString(preferenceKey, encrypt(serverKey, encodedSession))
        }
        check(editor.commit()) { "Failed to persist the remote session" }
    }

    private fun encrypt(serverKey: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(serverKey.encodeToByteArray())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return listOf(cipher.iv, ciphertext)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(serverKey: String, stored: String): String {
        val parts = stored.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid encrypted remote session" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(serverKey.encodeToByteArray())
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun preferenceKey(serverKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverKey.encodeToByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val PREFERENCES_NAME = "gromozeka.remote-session"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.gromozeka.remote-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val SEPARATOR = "."
    }
}
