package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User

interface ConversationSearchRepository {
    suspend fun search(
        request: ConversationSearchRequest,
        readableProjectIds: Set<Project.Id>,
        participantUserId: User.Id,
    ): ConversationSearchPage
}
