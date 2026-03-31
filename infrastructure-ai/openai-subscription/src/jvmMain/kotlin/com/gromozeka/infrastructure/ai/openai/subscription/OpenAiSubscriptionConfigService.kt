package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File

@Service
class OpenAiSubscriptionConfigService(
    private val settingsProvider: SettingsProvider,
) {
    private val log = KLoggers.logger(this)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val configFile: File by lazy {
        File(settingsProvider.homeDirectory, "openai-subscription.json")
    }

    fun ensureConfigFileExists(): File {
        if (!configFile.exists()) {
            save(OpenAiSubscriptionConfig())
        }
        return configFile
    }

    fun configFilePath(): String = configFile.absolutePath

    fun load(): OpenAiSubscriptionConfig {
        return if (configFile.exists()) {
            try {
                json.decodeFromString<OpenAiSubscriptionConfig>(configFile.readText())
            } catch (e: Exception) {
                log.warn("Failed to load OpenAI subscription config: ${e.message}")
                OpenAiSubscriptionConfig()
            }
        } else {
            OpenAiSubscriptionConfig()
        }
    }

    fun save(config: OpenAiSubscriptionConfig) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(json.encodeToString(config))
    }

    fun clearSession() {
        save(
            load().copy(
                pendingVerifier = null,
                pendingState = null,
                pendingDeviceCode = null,
                accessToken = null,
                refreshToken = null,
                idToken = null,
                accountId = null,
                expiresAt = null,
            )
        )
    }

    fun updatePendingAuthorization(authorizationUrl: OpenAiSubscriptionAuthorizationUrl) {
        save(
            load().copy(
                pendingDeviceCode = null,
                pendingVerifier = authorizationUrl.verifier,
                pendingState = authorizationUrl.state,
            )
        )
    }

    fun clearPendingAuthorization() {
        save(
            load().copy(
                pendingVerifier = null,
                pendingState = null,
            )
        )
    }

    fun clearPendingDeviceCode() {
        save(load().copy(pendingDeviceCode = null))
    }

    fun updatePendingDeviceCode(deviceCode: OpenAiSubscriptionPendingDeviceCode) {
        save(
            load().copy(
                pendingVerifier = null,
                pendingState = null,
                pendingDeviceCode = deviceCode,
            )
        )
    }

    fun getPendingDeviceCode(): OpenAiSubscriptionPendingDeviceCode? = load().pendingDeviceCode

    fun updateSession(session: OpenAiSubscriptionSession) {
        save(
            load().copy(
                pendingVerifier = null,
                pendingState = null,
                pendingDeviceCode = null,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                idToken = session.idToken,
                accountId = session.accountId,
                expiresAt = session.expiresAt,
            )
        )
    }

    fun getSession(): OpenAiSubscriptionSession? {
        val config = load()
        val accessToken = config.accessToken ?: return null
        val refreshToken = config.refreshToken ?: return null
        val expiresAt = config.expiresAt ?: return null

        return OpenAiSubscriptionSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = config.idToken,
            accountId = config.accountId,
            expiresAt = expiresAt,
        )
    }
}
