package com.gromozeka.infrastructure.ai.config

import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenAiConfig {

    data class OpenAiSettings(
        val apiKey: String = "dummy",
        val baseUrl: String = ""
    )

    private val settings = OpenAiSettings()

    @Bean
    fun openAiApi(): OpenAiApi {
        return OpenAiApi.builder()
            .apiKey(settings.apiKey)
            .baseUrl(settings.baseUrl)
            .build()
    }

}


