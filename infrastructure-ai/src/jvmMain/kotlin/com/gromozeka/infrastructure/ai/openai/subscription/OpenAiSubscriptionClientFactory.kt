package com.gromozeka.infrastructure.ai.openai.subscription

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.springframework.stereotype.Component

@Component
class OpenAiSubscriptionClientFactory {

    fun create(session: OpenAiSubscriptionSession): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .apiKey(session.accessToken)
            .baseUrl(CODEX_BASE_URL)
            .responseValidation(false)
            .putHeader("OpenAI-Beta", "responses=experimental")
            .putHeader("originator", "gromozeka")
            .build()
    }

    private companion object {
        const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
    }
}
