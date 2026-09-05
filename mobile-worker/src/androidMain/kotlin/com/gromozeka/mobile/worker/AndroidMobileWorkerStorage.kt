package com.gromozeka.mobile.worker

import android.content.Context
import java.io.File

class AndroidMobileWorkerStorage(context: Context) : MobileWorkerStorage {
    private val preferences = context.applicationContext.getSharedPreferences("gromozeka_mobile_worker", Context.MODE_PRIVATE)
    private val state = AndroidWorkerEncryptedFile(File(context.applicationContext.noBackupFilesDir, "worker-state.enc").toPath())

    override fun readState(): String? = state.read()

    override fun writeState(value: String) = state.write(value)

    @Synchronized
    override fun readCredential(): String? = preferences.getString(CREDENTIAL_KEY, null)?.let(AndroidWorkerCipher::decrypt)

    @Synchronized
    override fun writeCredential(value: String) {
        require(value.isNotBlank()) { "Mobile Worker credential must not be blank" }
        check(preferences.edit().putString(CREDENTIAL_KEY, AndroidWorkerCipher.encrypt(value)).commit()) {
            "Mobile Worker credential could not be persisted"
        }
    }

    @Synchronized
    override fun clearCredential() {
        check(preferences.edit().remove(CREDENTIAL_KEY).commit()) { "Mobile Worker credential could not be removed" }
    }

    private companion object { const val CREDENTIAL_KEY = "credential" }
}
