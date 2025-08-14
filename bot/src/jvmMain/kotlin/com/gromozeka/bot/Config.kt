package com.gromozeka.bot

import com.gromozeka.bot.services.*
import com.gromozeka.bot.viewmodel.AppViewModel
import com.gromozeka.shared.audio.AudioRecorder
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Config {


    // @Bean
    // fun chatDatabase(): ChatDatabase {
    //     val dbFile = File("chat.db")
    //     val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    //     if (!dbFile.exists()) {
    //         ChatDatabase.Schema.create(driver)
    //     }
    //     return ChatDatabase(driver)
    // }


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
    ) = TtsService(openAiAudioSpeechModel, settingsService)

    @Bean
    fun httpClient() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    @Bean
    fun chatClient(chatModel: OpenAiChatModel) = ChatClient.create(chatModel)

    @Bean
    fun pttService(
        audioRecorder: AudioRecorder,
        sttService: SttService,
        settingsService: SettingsService,
        audioMuteManager: AudioMuteManager,
    ) = PTTService(audioRecorder, sttService, settingsService, audioMuteManager)

    @Bean
    fun appViewModel(
        sessionManager: SessionManager,
        settingsService: SettingsService,
        @Qualifier("coroutineScope") scope: CoroutineScope,
    ) = AppViewModel(sessionManager, settingsService, scope)

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
        service.initialize()  // Инициализируем сразу при создании бина
        return service
    }

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

}