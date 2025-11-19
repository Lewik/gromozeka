package com.gromozeka.infrastructure.ai.config

import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OllamaConfig {

    // DISABLED: Ollama requires local installation which may not be available
    // Uncomment when Ollama is installed and running locally
    /*
    @Bean
    fun ollamaApi(
        @Value("\${spring.ai.ollama.base-url}") baseUrl: String
    ): OllamaApi {
        return OllamaApi.builder()
            .baseUrl(baseUrl)
            .build()
    }
    */
}
