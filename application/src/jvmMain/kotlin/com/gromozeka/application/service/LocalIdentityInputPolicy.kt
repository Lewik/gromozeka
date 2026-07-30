package com.gromozeka.application.service

import java.util.Locale

internal object LocalIdentityInputPolicy {
    private val usernamePattern = Regex("[a-z0-9][a-z0-9._-]*")
    private val usernameLength = 3..128
    private val passwordLength = 12..1024
    private const val maxDisplayNameLength = 255

    fun normalizeUsername(username: String): String {
        val normalized = username.trim().lowercase(Locale.ROOT)
        require(normalized.length in usernameLength) {
            "Username must contain ${usernameLength.first} to ${usernameLength.last} characters"
        }
        require(normalized.matches(usernamePattern)) {
            "Username may contain lowercase letters, numbers, dots, underscores, and hyphens"
        }
        return normalized
    }

    fun normalizeDisplayName(displayName: String, fallback: String): String =
        displayName.trim()
            .ifEmpty { fallback }
            .also {
                require(it.length <= maxDisplayNameLength) {
                    "Display name must not exceed $maxDisplayNameLength characters"
                }
            }

    fun validatePassword(password: CharArray) {
        require(password.size in passwordLength) {
            "Password must contain ${passwordLength.first} to ${passwordLength.last} characters"
        }
    }
}
