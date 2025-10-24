package com.gromozeka.bot

import com.gromozeka.bot.services.SettingsService
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ModelsConfig {

    @Bean
    fun openAiAudioTranscriptionModel(settingsService: SettingsService): OpenAiAudioTranscriptionModel {
        val apiKey = settingsService.settings.openAiApiKey ?: ""
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(apiKey).build()
        return OpenAiAudioTranscriptionModel(openAiAudioApi)
    }

    @Bean
    fun openAiAudioSpeechModel(settingsService: SettingsService): OpenAiAudioSpeechModel {
        val apiKey = settingsService.settings.openAiApiKey ?: ""
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(apiKey).build()
        return OpenAiAudioSpeechModel(openAiAudioApi)
    }
}
