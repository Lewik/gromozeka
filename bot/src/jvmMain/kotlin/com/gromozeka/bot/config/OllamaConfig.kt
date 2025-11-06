package com.gromozeka.bot.config

import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OllamaConfig {

    @Bean
    @Qualifier("ollamaChatModel")
    fun ollamaChatModel(
        ollamaChatModelAutoconfigured: OllamaChatModel
    ): OllamaChatModel {
        return ollamaChatModelAutoconfigured
    }
}
