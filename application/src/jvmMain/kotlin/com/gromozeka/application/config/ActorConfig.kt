package com.gromozeka.application.config

import com.gromozeka.application.actor.ConversationSupervisor
import com.gromozeka.application.service.MessageConversionService
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.service.McpToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.ai.tool.ToolCallback

/**
 * Spring configuration for actor-based components.
 */
@Configuration
class ActorConfig {
    
    /**
     * Create ConversationSupervisor bean.
     * 
     * Supervisor manages lifecycle of all ConversationEngine instances.
     * Started automatically by calling start() after bean creation.
     */
    @Bean
    fun conversationSupervisor(
        @Qualifier("supervisorScope") scope: CoroutineScope,
        conversationRepository: ConversationRepository,
        messageRepository: MessageRepository,
        threadRepository: ThreadRepository,
        threadMessageRepository: ThreadMessageRepository,
        projectRepository: ProjectRepository,
        chatModelProvider: ChatModelProvider,
        agentDomainService: AgentDomainService,
        parallelToolExecutor: ParallelToolExecutor,
        messageConversionService: MessageConversionService,
        toolCallbacks: List<ToolCallback>,
        mcpToolProvider: McpToolProvider
    ): ConversationSupervisor {
        val supervisor = ConversationSupervisor(
            scope = scope,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            threadRepository = threadRepository,
            threadMessageRepository = threadMessageRepository,
            projectRepository = projectRepository,
            chatModelProvider = chatModelProvider,
            agentDomainService = agentDomainService,
            parallelToolExecutor = parallelToolExecutor,
            messageConversionService = messageConversionService,
            toolCallbacks = toolCallbacks,
            mcpToolProvider = mcpToolProvider,
            maxIterations = 200
        )
        
        // Start supervisor command processing loop
        supervisor.start()
        
        return supervisor
    }
    
    /**
     * Dedicated CoroutineScope for ConversationSupervisor.
     * 
     * Uses SupervisorJob to prevent failure propagation between engines.
     * Uses Dispatchers.Default for CPU-bound work (event broadcasting).
     */
    @Bean
    @Qualifier("supervisorScope")
    fun supervisorScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
