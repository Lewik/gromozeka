package com.gromozeka.bot.services

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.Project
import com.gromozeka.shared.repository.ConversationRepository
import com.gromozeka.shared.repository.ProjectRepository
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class ConversationSearchService(
    private val conversationTreeRepository: ConversationRepository,
    private val projectRepository: ProjectRepository,
) {
    private val log = KLoggers.logger(this)

    suspend fun searchConversations(query: String): List<Pair<Conversation, Project>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val lowerQuery = query.lowercase().trim()

        val projects = projectRepository.findAll()
        val projectsMap = projects.associateBy { it.id }

        val results = mutableListOf<Pair<Conversation, Project>>()

        projects.forEach { project ->
            val projectMatches = project.name.lowercase().contains(lowerQuery) ||
                    project.path.lowercase().contains(lowerQuery)

            val conversations = conversationTreeRepository.findByProject(project.id)

            conversations.forEach { conversation ->
                val displayName = conversation.displayName
                val effectiveName = if (displayName.isBlank()) "New Conversation" else displayName

                val conversationMatches =
                    displayName.lowercase().contains(lowerQuery) ||
                            effectiveName.lowercase().contains(lowerQuery)

                if (projectMatches || conversationMatches) {
                    results.add(conversation to project)
                }
            }
        }

        results.sortedWith(
            compareByDescending<Pair<Conversation, Project>> { (conversation, _) ->
                val effectiveName = conversation.displayName.takeIf { it.isNotBlank() } ?: "New Conversation"
                effectiveName.lowercase() == lowerQuery
            }.thenByDescending { (conversation, _) ->
                conversation.updatedAt
            }
        )
    }
}
