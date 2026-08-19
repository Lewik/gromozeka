package com.gromozeka.domain.service

import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest

interface ConversationSearchService {
    suspend fun search(request: ConversationSearchRequest): ConversationSearchPage
}
