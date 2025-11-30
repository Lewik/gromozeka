package com.gromozeka.infrastructure.ai.config

import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenAiConfig {

    @Bean
    fun openAiApi(
    ): OpenAiApi {
        return OpenAiApi.builder()
            .apiKey("")
            .build()
    }

}


