package com.gromozeka.presentation.services

class LogEncryptor {
    data class EncryptionResult(
        val success: Boolean,
        val encryptedFile: String? = null,
        val error: String? = null,
    )

    suspend fun encryptLogs(): EncryptionResult =
        EncryptionResult(success = false, error = "Log encryption is disabled in the remote UI client")
}
