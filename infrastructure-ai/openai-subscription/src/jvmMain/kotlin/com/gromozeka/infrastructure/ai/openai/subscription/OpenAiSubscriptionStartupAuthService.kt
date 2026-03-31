package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.service.SettingsProvider
import jakarta.annotation.PostConstruct
import klog.KLoggers
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class OpenAiSubscriptionStartupAuthService(
    private val settingsProvider: SettingsProvider,
    private val configService: OpenAiSubscriptionConfigService,
    private val browserAuthService: OpenAiSubscriptionBrowserAuthService,
) {
    private val log = KLoggers.logger(this)

    @PostConstruct
    fun bootstrapIfNeeded() {
        if (settingsProvider.aiProvider != AIProvider.OPEN_AI_SUBSCRIPTION) {
            return
        }

        val configFile = configService.ensureConfigFileExists()
        val session = configService.getSession()
        if (session != null) {
            val expiresAt = Instant.ofEpochMilli(session.expiresAt)
            log.info("OpenAI subscription session loaded from ${configFile.absolutePath}")
            log.info("OpenAI subscription accountId=${session.accountId ?: "unknown"} expiresAt=$expiresAt")
            return
        }

        browserAuthService.beginLogin()
            .onSuccess { authorizationUrl ->
                log.warn("OPEN_AI_SUBSCRIPTION is selected, but no subscription session is configured yet.")
                log.warn("OpenAI subscription browser login URL:")
                log.warn(authorizationUrl.url)
                log.warn("Manual bootstrap file: ${configFile.absolutePath}")
                log.warn(
                    "Open the URL, sign in, and keep the application running. Gromozeka will finish the login automatically after the browser returns to localhost."
                )
            }
            .onFailure { error ->
                log.error(error) { "Failed to start OpenAI subscription browser login" }
            }
    }
}
