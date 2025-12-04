package com.gromozeka.infrastructure.ai.oauth

import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File

@Service
class OAuthConfigService(
    private val settingsProvider: SettingsProvider
) {
    private val log = KLoggers.logger(this)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val configFile: File by lazy {
        File(settingsProvider.homeDirectory, "oauth.json")
    }

    fun loadConfig(): OAuthConfig? {
        return if (configFile.exists()) {
            try {
                val content = configFile.readText()
                json.decodeFromString<OAuthConfig>(content)
            } catch (e: Exception) {
                log.warn("Failed to load OAuth config: ${e.message}")
                null
            }
        } else {
            log.info("OAuth config file not found")
            null
        }
    }

    fun saveConfig(config: OAuthConfig) {
        try {
            configFile.writeText(json.encodeToString(config))
            log.info("OAuth config saved to: ${configFile.absolutePath}")
        } catch (e: Exception) {
            log.error(e) { "Failed to save OAuth config" }
            throw e
        }
    }

    fun updateTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        val config = loadConfig()
        if (config != null) {
            val updated = config.copy(
                enabled = true,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt
            )
            saveConfig(updated)
        }
    }

    fun clearTokens() {
        val config = loadConfig()
        if (config != null) {
            val cleared = config.copy(
                accessToken = null,
                refreshToken = null,
                expiresAt = null
            )
            saveConfig(cleared)
        }
    }

    fun isOAuthEnabled(): Boolean {
        val config = loadConfig()
        return config?.enabled == true && config.accessToken != null
    }

    fun getConfig(): OAuthConfig? {
        return loadConfig()
    }
}
