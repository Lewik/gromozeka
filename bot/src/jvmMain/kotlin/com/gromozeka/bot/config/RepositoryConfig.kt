package com.gromozeka.bot.config

import com.gromozeka.bot.repository.exposed.*
import com.gromozeka.domain.repository.*
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RepositoryConfig {

    @Bean
    fun repositoryJson(): Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Bean
    fun projectRepository(repositoryJson: Json): ProjectRepository =
        ExposedProjectRepository(repositoryJson)

    @Bean
    fun conversationRepository(): ConversationRepository =
        ExposedConversationRepository()

    @Bean
    fun threadRepository(): ThreadRepository =
        ExposedThreadRepository()

    @Bean
    fun messageRepository(repositoryJson: Json): MessageRepository =
        ExposedMessageRepository(repositoryJson)

    @Bean
    fun threadMessageRepository(repositoryJson: Json): ThreadMessageRepository =
        ExposedThreadMessageRepository(repositoryJson)

    @Bean
    fun contextRepository(repositoryJson: Json): ContextRepository =
        ExposedContextRepository(repositoryJson)

    @Bean
    fun agentRepository(): AgentRepository =
        ExposedAgentRepository()

    @Bean
    fun squashOperationRepository(repositoryJson: Json): SquashOperationRepository =
        ExposedSquashOperationRepository(repositoryJson)

    @Bean
    fun tokenUsageStatisticsRepository(): TokenUsageStatisticsRepository =
        ExposedTokenUsageStatisticsRepository()
}
