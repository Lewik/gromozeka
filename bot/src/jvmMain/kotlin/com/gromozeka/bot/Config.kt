package com.gromozeka.bot

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gromozeka.bot.db.ChatDatabase
import com.gromozeka.bot.repository.ChatMessageContentRepository
import com.gromozeka.bot.repository.ChatMessageRepository
import com.gromozeka.bot.repository.FileMetadataRepository
import com.gromozeka.bot.repository.ThreadMetadataRepository
import com.gromozeka.bot.services.SttService
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiChatModel

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

@Configuration
class Config {


    @Bean
    fun chatDatabase(): ChatDatabase {
        val dbFile = File("chat.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (!dbFile.exists()) {
            ChatDatabase.Schema.create(driver)
        }
        return ChatDatabase(driver)
    }

    @Bean
    fun chatMessageRepository(chatDatabase: ChatDatabase) = ChatMessageRepository(chatDatabase)

    @Bean
    fun chatMessageContentRepository(chatDatabase: ChatDatabase) = ChatMessageContentRepository(chatDatabase)

    @Bean
    fun vectorFileMetadataRepository(chatDatabase: ChatDatabase) = FileMetadataRepository(chatDatabase)

    @Bean
    fun threadMetadataRepository(chatDatabase: ChatDatabase) = ThreadMetadataRepository(chatDatabase)

//    @Bean
//    fun chatMessageService(
//        chatMessageRepository: ChatMessageRepository,
//        chatMessageContentRepository: ChatMessageContentRepository,
//    ) = ChatMessageService(
//        chatMessageRepository,
//        chatMessageContentRepository,
//    )

//    @Bean
//    fun aiClient(@Value("\${spring.ai.openai.api-key}") apiKey: String) =
//        OpenAIOkHttpClientAsync.builder()
//            .apiKey(apiKey)
//            .build()


    @Bean
    fun chatMemory() = MessageWindowChatMemory.builder()
        .maxMessages(50)
            .build()

    @Bean
    fun sttService(openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel) =
        SttService(openAiAudioTranscriptionModel)

//
//    @Bean
//    fun vectorStoreFileService(aiClient: OpenAIClientAsync) = VectorStoreFileService(aiClient)
//
//    @Bean
//    fun fileStoreFileService(aiClient: OpenAIClientAsync) = FileStoreFileService(aiClient)

//    @Bean
//    fun fileMetadataService(
//        vectorStoreFileService: VectorStoreFileService,
//        fileStoreFileService: FileStoreFileService,
//        fileMetadataRepository: FileMetadataRepository,
//        @Value("\${root}") root: String,
//    ) = FileMetadataService(vectorStoreFileService, fileStoreFileService, fileMetadataRepository, root)

//    @Bean
//    fun threadService(
//        aiClient: OpenAIClientAsync,
//        vectorStoreFileService: VectorStoreFileService,
//        threadMetadataRepository: ThreadMetadataRepository,
//    ) = ThreadService(aiClient, vectorStoreFileService, threadMetadataRepository)


    @Bean
    fun chatClient(chatModel: OpenAiChatModel) = ChatClient.builder(chatModel).build()
    
    @Bean
    fun theAssistant(
        chatClient: ChatClient,
        chatMemory: ChatMemory
    ) = TheAssistant(
        chatClient,
        chatMemory
    )
//    @Bean
//    fun theAssistant(
//        aiClient: OpenAIClientAsync,
//        chatMessageService: ChatMessageService,
//        fileMetadataService: FileMetadataService,
//        vectorStoreFileService: VectorStoreFileService,
//        threadService: ThreadService,
//    ) = TheAssistant(
//        aiClient,
//        chatMessageService,
//        fileMetadataService,
//        vectorStoreFileService,
//        threadService,
//    )

}