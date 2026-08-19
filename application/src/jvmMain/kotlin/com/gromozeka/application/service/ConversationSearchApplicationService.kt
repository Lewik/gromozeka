package com.gromozeka.application.service

import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationSearchRepository
import com.gromozeka.domain.service.ProjectAccessService
import org.springframework.stereotype.Service

@Service
class ConversationSearchApplicationService(
    private val projectAccessService: ProjectAccessService,
    private val conversationSearchRepository: ConversationSearchRepository,
) {
    suspend fun search(
        actorUserId: User.Id,
        request: ConversationSearchRequest,
    ): ConversationSearchPage {
        val readableProjectIds = projectAccessService.findAll(actorUserId)
            .mapTo(linkedSetOf()) { it.id }
        if (readableProjectIds.isEmpty()) return ConversationSearchPage(emptyList())

        return conversationSearchRepository.search(
            request = request.copy(query = request.query.trim()),
            readableProjectIds = readableProjectIds,
        )
    }
}
