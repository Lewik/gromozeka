package com.gromozeka.bot.config

import com.gromozeka.bot.services.GitService
import com.gromozeka.shared.repository.AgentRepository
import com.gromozeka.shared.repository.ContextRepository
import com.gromozeka.shared.repository.ConversationTreeRepository
import com.gromozeka.shared.repository.ProjectRepository
import com.gromozeka.shared.services.AgentService
import com.gromozeka.shared.services.ContextService
import com.gromozeka.shared.services.ConversationTreeService
import com.gromozeka.shared.services.ProjectService
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
    fun conversationTreeService(
        conversationTreeRepository: ConversationTreeRepository,
        projectService: ProjectService
    ) = ConversationTreeService(conversationTreeRepository, projectService)

    @Bean
    fun contextService(
        contextRepository: ContextRepository,
        projectService: ProjectService
    ) = ContextService(contextRepository, projectService)
}
