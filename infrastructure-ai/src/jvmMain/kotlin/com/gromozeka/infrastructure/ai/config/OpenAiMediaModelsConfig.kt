package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.springai.SttService
import com.gromozeka.infrastructure.ai.springai.TtsService
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenAiMediaModelsConfig {

    @Bean
    fun openAiAudioTranscriptionModel(settingsProvider: SettingsProvider): OpenAiAudioTranscriptionModel {
        val apiKey = settingsProvider.openAiApiKey ?: ""
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(apiKey).build()
        return OpenAiAudioTranscriptionModel(openAiAudioApi)
    }

    @Bean
    fun openAiAudioSpeechModel(settingsProvider: SettingsProvider): OpenAiAudioSpeechModel {
        val apiKey = settingsProvider.openAiApiKey ?: ""
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(apiKey).build()
        return OpenAiAudioSpeechModel(openAiAudioApi)
    }

    @Bean
    fun openAiEmbeddingModel(settingsProvider: SettingsProvider): EmbeddingModel {
        val apiKey = settingsProvider.openAiApiKey ?: ""
        return OpenAiEmbeddingModel(
            OpenAiApi.builder().apiKey(apiKey).build(),
            org.springframework.ai.document.MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder()
                .model("text-embedding-3-large")
                .build()
        )
    }

    @Bean
    fun sttService(
        openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel,
        settingsProvider: SettingsProvider,
    ) = SttService(openAiAudioTranscriptionModel, settingsProvider)

    @Bean
    fun ttsService(
        openAiAudioSpeechModel: OpenAiAudioSpeechModel,
        settingsProvider: SettingsProvider,
        audioPlayerController: com.gromozeka.domain.service.AudioController,
    ) = TtsService(openAiAudioSpeechModel, settingsProvider, audioPlayerController)
}
