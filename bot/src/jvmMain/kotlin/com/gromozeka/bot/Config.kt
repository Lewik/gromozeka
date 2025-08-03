package com.gromozeka.bot

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gromozeka.bot.db.ChatDatabase
import com.gromozeka.bot.services.SttService
import com.gromozeka.bot.services.TtsService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

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
    fun sttService(openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel) =
        SttService(openAiAudioTranscriptionModel)

    @Bean
    fun ttsService(openAiAudioSpeechModel: OpenAiAudioSpeechModel) =
        TtsService(openAiAudioSpeechModel)

    @Bean
    fun httpClient() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    @Bean
    fun chatClient(chatModel: OpenAiChatModel) = ChatClient.builder(chatModel).build()


}