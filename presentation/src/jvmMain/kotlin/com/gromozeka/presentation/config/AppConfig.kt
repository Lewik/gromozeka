package com.gromozeka.presentation.config

import com.gromozeka.infrastructure.ai.platform.AudioPlayerController
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.infrastructure.ai.platform.SystemAudioController
import com.gromozeka.presentation.services.WindowStateService
import com.gromozeka.infrastructure.ai.service.OllamaModelService
import com.gromozeka.presentation.services.PTTEventRouter
import com.gromozeka.presentation.services.PTTService
import com.gromozeka.application.service.ToolApprovalService
import com.gromozeka.presentation.services.TtsQueue
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationNameSearchService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.infrastructure.ai.springai.SttService
import com.gromozeka.infrastructure.ai.springai.TtsService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Config {


    @Bean
    fun audioRecorder() = AudioRecorder()

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
        settingsService: com.gromozeka.domain.service.SettingsService,
        systemAudioController: SystemAudioController,
    ) = PTTService(audioRecorder, sttService, settingsService, systemAudioController)

    @Bean
    fun appViewModel(
        conversationRuntimeService: ConversationRuntimeService,
        conversationService: ConversationDomainService,
        messageSquashGenerationService: MessageSquashGenerationService,
        soundNotificationService: com.gromozeka.presentation.services.SoundNotificationPlayer,
        settingsService: com.gromozeka.domain.service.SettingsService,
        @Qualifier("coroutineScope") scope: CoroutineScope,
        screenCaptureController: ScreenCaptureController,
        defaultAgentProvider: DefaultAgentProvider,
        tokenStatsService: ConversationTokenStatsService,
    ) = AppViewModel(
        conversationRuntimeService,
        conversationService,
        messageSquashGenerationService,
        soundNotificationService,
        settingsService,
        scope,
        screenCaptureController,
        defaultAgentProvider,
        tokenStatsService,
    )

    @Bean
    fun conversationSearchViewModel(
        conversationNameSearchService: ConversationNameSearchService,
        @Qualifier("coroutineScope") scope: CoroutineScope,
    ) = ConversationSearchViewModel(conversationNameSearchService, scope)

    @Bean
    fun pttEventRouter(
        pttService: PTTService,
        ttsQueueService: TtsQueue,
        appViewModel: AppViewModel,
        settingsService: com.gromozeka.domain.service.SettingsService,
    ) = PTTEventRouter(pttService, ttsQueueService, appViewModel, settingsService)

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @Bean
    fun translationService(settingsService: com.gromozeka.domain.service.SettingsService): TranslationService {
        val service = TranslationService()
        service.init(settingsService)
        return service
    }

    @Bean
    fun themeService(settingsService: com.gromozeka.domain.service.SettingsService): ThemeService {
        val service = ThemeService()
        service.init(settingsService)
        return service
    }

    @Bean
    fun ollamaModelService() = OllamaModelService()

}
