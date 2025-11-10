package com.gromozeka.bot

import com.gromozeka.bot.platform.AudioPlayerController
import com.gromozeka.bot.platform.ScreenCaptureController
import com.gromozeka.bot.platform.SystemAudioController
import com.gromozeka.bot.services.*
import com.gromozeka.bot.services.theming.ThemeService
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.bot.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.shared.repository.TokenUsageStatisticsRepository
import com.gromozeka.shared.services.ConversationService
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
        conversationService: ConversationService,
        soundNotificationService: SoundNotificationService,
        settingsService: SettingsService,
        @Qualifier("coroutineScope") scope: CoroutineScope,
        screenCaptureController: ScreenCaptureController,
        defaultAgentProvider: DefaultAgentProvider,
        tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    ) = AppViewModel(
        conversationEngineService,
        conversationService,
        soundNotificationService,
        settingsService,
        scope,
        screenCaptureController,
        defaultAgentProvider,
        tokenUsageStatisticsRepository,
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
