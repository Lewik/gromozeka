package com.gromozeka.bot.config

import com.gromozeka.bot.services.GitService
import com.gromozeka.shared.repository.*
import com.gromozeka.shared.services.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ServiceConfig {

    @Bean
    fun projectService(
        projectRepository: ProjectRepository
    ) = ProjectService(projectRepository)

    @Bean
    fun gitService() = GitService()

    @Bean
    fun agentService(
        agentRepository: AgentRepository
    ) = AgentService(agentRepository)

    @Bean
    fun conversationService(
        conversationRepo: ConversationRepository,
        threadRepo: ThreadRepository,
        messageRepo: MessageRepository,
        threadMessageRepo: ThreadMessageRepository,
        projectService: ProjectService
    ) = ConversationService(conversationRepo, threadRepo, messageRepo, threadMessageRepo, projectService)

    @Bean
    fun contextService(
        contextRepository: ContextRepository,
        projectService: ProjectService
    ) = ContextService(contextRepository, projectService)
}
