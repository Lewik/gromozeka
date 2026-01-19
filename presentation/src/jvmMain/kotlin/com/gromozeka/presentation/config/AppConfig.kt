package com.gromozeka.presentation.config

import com.gromozeka.infrastructure.ai.platform.AudioPlayerController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.platform.SystemAudioController
import com.gromozeka.presentation.services.SettingsService
import com.gromozeka.presentation.services.WindowStateService
import com.gromozeka.application.service.ConversationEngineService
import com.gromozeka.application.service.ConversationSearchService
import com.gromozeka.application.service.DefaultAgentProvider
import com.gromozeka.application.service.MessageSquashService
import com.gromozeka.infrastructure.ai.service.OllamaModelService
import com.gromozeka.presentation.services.PTTEventRouter
import com.gromozeka.presentation.services.PTTService
import com.gromozeka.presentation.services.SoundNotificationService
import com.gromozeka.application.service.ToolApprovalService
import com.gromozeka.presentation.services.TTSQueueService
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphTool
import com.gromozeka.infrastructure.ai.springai.SttService
import com.gromozeka.infrastructure.ai.springai.TtsService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Config {


    @Bean
    fun audioRecorder() = AudioRecorder()

    @Bean
    fun sttService(
        openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel,
        settingsService: SettingsService,
    ) = SttService(openAiAudioTranscriptionModel, settingsService)

    @Bean
    fun ttsService(
        openAiAudioSpeechModel: OpenAiAudioSpeechModel,
        settingsService: SettingsService,
        audioPlayerController: AudioPlayerController,
    ) = TtsService(openAiAudioSpeechModel, settingsService, audioPlayerController)

    @Bean
    fun httpClient() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }

//    @Bean
//    fun chatClient(chatModel: OpenAiChatModel) = ChatClient.create(chatModel)

    @Bean
    fun pttService(
        audioRecorder: AudioRecorder,
        sttService: SttService,
        settingsService: SettingsService,
        systemAudioController: SystemAudioController,
    ) = PTTService(audioRecorder, sttService, settingsService, systemAudioController)

    @Bean
    fun appViewModel(
        conversationEngineService: ConversationEngineService,
        conversationService: ConversationDomainService,
        messageSquashService: MessageSquashService,
        soundNotificationService: SoundNotificationService,
        agentDomainService: com.gromozeka.domain.service.AgentDomainService,
        settingsService: SettingsService,
        @Qualifier("coroutineScope") scope: CoroutineScope,
        screenCaptureController: ScreenCaptureController,
        defaultAgentProvider: DefaultAgentProvider,
        tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
        indexDomainToGraphTool: IndexDomainToGraphTool,
    ) = AppViewModel(
        conversationEngineService,
        conversationService,
        messageSquashService,
        soundNotificationService,
        agentDomainService,
        settingsService,
        scope,
        screenCaptureController,
        defaultAgentProvider,
        tokenUsageStatisticsRepository,
        indexDomainToGraphTool,
    )

    @Bean
    fun conversationSearchViewModel(
        conversationSearchService: ConversationSearchService,
        @Qualifier("coroutineScope") scope: CoroutineScope,
    ) = ConversationSearchViewModel(conversationSearchService, scope)

    @Bean
    fun pttEventRouter(
        pttService: PTTService,
        ttsQueueService: TTSQueueService,
        appViewModel: AppViewModel,
        settingsService: SettingsService,
    ) = PTTEventRouter(pttService, ttsQueueService, appViewModel, settingsService)

    @Bean
    fun settingsService(): SettingsService {
        val service = SettingsService()
        service.initialize()
        return service
    }

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @Bean
    fun translationService(settingsService: SettingsService): TranslationService {
        val service = TranslationService()
        service.init(settingsService)
        return service
    }

    @Bean
    fun themeService(settingsService: SettingsService): ThemeService {
        val service = ThemeService()
        service.init(settingsService)
        return service
    }

    @Bean
    fun ollamaModelService() = OllamaModelService()


}
