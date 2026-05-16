package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
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
        val apiKey = settingsProvider.openAiApiKeyForMedia()
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(apiKey).build()
        return OpenAiAudioTranscriptionModel(openAiAudioApi)
    }

    @Bean
    fun openAiAudioSpeechModel(settingsProvider: SettingsProvider): OpenAiAudioSpeechModel {
        val apiKey = settingsProvider.openAiApiKeyForMedia()
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(apiKey).build()
        return OpenAiAudioSpeechModel(openAiAudioApi)
    }

    @Bean
    fun openAiEmbeddingModel(settingsProvider: SettingsProvider): EmbeddingModel {
        val runtime = settingsProvider.findFirstModelConfiguration(
            AiModelConfiguration.Role.EMBEDDINGS,
            AiProvider.OPENAI
        )
        val apiKey = runtime?.connection?.apiKeyRef()?.let(settingsProvider::resolveSecret) ?: ""
        val modelName = runtime?.modelConfiguration?.providerModelId ?: "text-embedding-3-large"
        return OpenAiEmbeddingModel(
            OpenAiApi.builder().apiKey(apiKey).build(),
            org.springframework.ai.document.MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder()
                .model(modelName)
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

private fun SettingsProvider.openAiApiKeyForMedia(): String =
    (findFirstModelConfiguration(AiModelConfiguration.Role.SPEECH_TO_TEXT, AiProvider.OPENAI)?.connection?.apiKeyRef()
        ?: findFirstModelConfiguration(AiModelConfiguration.Role.TEXT_TO_SPEECH, AiProvider.OPENAI)?.connection?.apiKeyRef()
        ?: userProfile.aiSettings.connections.firstOrNull {
            it.enabled && it.kind == AiConnection.Kind.OPENAI_API
        }?.apiKeyRef())
        ?.let { resolveSecret(it) }
        ?: ""

private fun AiConnection.apiKeyRef(): SecretRef? =
    (this as? AiConnection.ApiKeyAiConnection)?.apiKey
