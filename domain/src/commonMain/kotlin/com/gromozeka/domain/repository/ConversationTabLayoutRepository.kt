package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ConversationTabLayout

interface ConversationTabLayoutRepository {
    suspend fun load(): ConversationTabLayout

    suspend fun save(layout: ConversationTabLayout): ConversationTabLayout
}
