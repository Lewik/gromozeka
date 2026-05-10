package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project

interface ConversationNameSearchService {
    suspend fun searchConversations(query: String): List<Pair<Conversation, Project>>
}
