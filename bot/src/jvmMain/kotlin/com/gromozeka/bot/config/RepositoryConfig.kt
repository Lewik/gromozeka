package com.gromozeka.bot.config

import com.gromozeka.bot.repository.exposed.ExposedAgentRepository
import com.gromozeka.bot.repository.exposed.ExposedContextRepository
import com.gromozeka.bot.repository.exposed.ExposedConversationTreeRepository
import com.gromozeka.bot.repository.exposed.ExposedProjectRepository
import com.gromozeka.shared.repository.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RepositoryConfig {

    @Bean
    fun json(): Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Bean
    fun projectRepository(database: Database, json: Json): ProjectRepository =
        ExposedProjectRepository(database, json)

    @Bean
    fun conversationTreeRepository(database: Database, json: Json): ConversationTreeRepository =
        ExposedConversationTreeRepository(database, json)

    @Bean
    fun contextRepository(database: Database, json: Json): ContextRepository =
        ExposedContextRepository(database, json)

    @Bean
    fun agentRepository(database: Database): AgentRepository =
        ExposedAgentRepository(database)
}
