package com.gromozeka.infrastructure.ai.config

import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AnthropicConfig {

    @Bean
    fun anthropicApi(
    ): AnthropicApi {
        return AnthropicApi.builder()
            .apiKey("")
            .build()
    }

}


