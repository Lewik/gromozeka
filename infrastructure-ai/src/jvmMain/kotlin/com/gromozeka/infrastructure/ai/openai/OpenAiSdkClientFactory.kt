package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.SettingsProvider
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

        val apiKey = settingsProvider.resolveSecret((connection as? AiConnection.ApiKeyAiConnection)?.apiKey)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: error("OpenAI API key is not configured for connection ${connection.id.value}")

        return OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .apply {
                (connection as? AiConnection.HttpAiConnection)
                    ?.baseUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::baseUrl)
            }
            .build()
    }
}

internal fun AiConnection.apiKeyRef(): SecretRef? =
    (this as? AiConnection.ApiKeyAiConnection)?.apiKey
