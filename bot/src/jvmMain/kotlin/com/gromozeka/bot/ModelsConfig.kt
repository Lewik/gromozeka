package com.gromozeka.bot

import com.gromozeka.bot.services.SettingsService
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ModelsConfig {
    @Bean
    fun openAiApiKey(settingsService: SettingsService): String? {
        return settingsService.settingsFlow.value?.openAiApiKey
    }

    @Bean
    fun openAiAudioTranscriptionModel(openAiApiKey: String?): OpenAiAudioTranscriptionModel? {
        return if (openAiApiKey != null) {
            val openAiAudioApi = OpenAiAudioApi.builder().apiKey(openAiApiKey).build()
            OpenAiAudioTranscriptionModel(openAiAudioApi)
        } else {
            null
        }
    }

    @Bean
    fun openAiAudioSpeechModel(openAiApiKey: String?): OpenAiAudioSpeechModel? {
        return if (openAiApiKey != null) {
            val openAiAudioApi = OpenAiAudioApi.builder().apiKey(openAiApiKey).build()
            OpenAiAudioSpeechModel(openAiAudioApi)
        } else {
            null
        }
    }

    @Bean
    fun openAiChatModel(openAiApiKey: String?): OpenAiChatModel? {
        return if (openAiApiKey != null) {
            val openAiApi = OpenAiApi.builder()
                .apiKey(openAiApiKey)
                .build()

//        val model = "gpt-4.1"
//        val model = OpenAiApi.ChatModel.GPT_4_O
            val model = OpenAiApi.ChatModel.GPT_4_O_MINI  // somewhat dumb and cheap
////            .model("gpt-4.5-preview") // EXPENSIVE!!! and very slow
            OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build()
        } else {
            null
        }
    }
}