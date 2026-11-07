package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpSupport
import com.openai.core.LogLevel
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.springframework.stereotype.Component

@Component
class OpenAiSdkClientFactory(
    private val settingsProvider: SettingsProvider,
) {
    fun createClient(connection: AiConnection): OpenAIClient {
        require(connection.kind == AiConnection.Kind.OPENAI_API || connection.kind == AiConnection.Kind.OPENAI_COMPATIBLE) {
            "OpenAI SDK client requires OpenAI API-compatible connection, got ${connection.kind}"
        }

        val settings = SdkHttpSupport.settings(connection)
        val baseUrl = resolveBaseUrl(connection)
        val token = resolveApiKey(connection)
        val tls = SdkHttpSupport.tls(settings)
        return OpenAIOkHttpClient.builder()
            .apiKey(token)
            .baseUrl(baseUrl)
            .logLevel(LogLevel.OFF)
            .proxy(SdkHttpSupport.proxy(settings, baseUrl))
            .apply {
                tls?.let { sslSocketFactory(it.socketFactory); trustManager(it.trustManager) }
                SdkHttpSupport.headers(settings).forEach { (name, value) -> replaceHeaders(name, value) }
            }
            .build()
    }

    fun resolveApiKey(connection: AiConnection): String =
        SdkHttpSupport.token(connection, settingsProvider, "OPENAI_API_KEY")

    fun resolveBaseUrl(connection: AiConnection): String =
        SdkHttpSupport.baseUrl(connection, DEFAULT_OPENAI_BASE_URL)

    private companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
    }
}

internal fun AiConnection.apiKeyRef(): SecretRef? =
    (this as? AiConnection.ApiKeyAiConnection)?.apiKey
