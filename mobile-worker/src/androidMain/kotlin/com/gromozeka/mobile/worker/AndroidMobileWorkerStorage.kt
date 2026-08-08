package com.gromozeka.mobile.worker

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class AndroidMobileWorkerStorage(context: Context) : MobileWorkerStorage {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun readState(): String? = preferences.getString(STATE_KEY, null)?.let(::decrypt)

    @Synchronized
    override fun writeState(value: String) {
        check(preferences.edit().putString(STATE_KEY, encrypt(value)).commit()) {
            "Mobile Worker state could not be persisted"
        }
    }

    @Synchronized
    override fun readCredential(): String? {
        val encoded = preferences.getString(CREDENTIAL_KEY, null) ?: return null
        return decrypt(encoded)
    }

    @Synchronized
    override fun writeCredential(value: String) {
        require(value.isNotBlank()) { "Mobile Worker credential must not be blank" }
        check(preferences.edit().putString(CREDENTIAL_KEY, encrypt(value)).commit()) {
            "Mobile Worker credential could not be persisted"
        }
    }

    @Synchronized
    override fun clearCredential() {
        check(preferences.edit().remove(CREDENTIAL_KEY).commit()) {
            "Mobile Worker credential could not be removed"
        }
    }

    private fun decrypt(encoded: String): String {
        val (iv, ciphertext) = encoded.split(':', limit = 2).takeIf { it.size == 2 }
            ?: error("Stored Mobile Worker data is malformed")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            credentialKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).decodeToString()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, credentialKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun credentialKey(): SecretKey {
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
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "gromozeka_mobile_worker"
        const val STATE_KEY = "state"
        const val CREDENTIAL_KEY = "credential"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gromozeka.mobile.worker.credential"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
