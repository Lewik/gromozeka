package com.gromozeka.bot

import com.gromozeka.bot.services.SettingsService
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
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

    @Bean
    fun openAiChatModel(settingsService: SettingsService): OpenAiChatModel {
        val apiKey = settingsService.settings.openAiApiKey ?: ""
        val openAiApi = OpenAiApi.builder().apiKey(apiKey).build()
        val model = OpenAiApi.ChatModel.GPT_4_O_MINI

        return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder().model(model).build())
            .build()
    }
}