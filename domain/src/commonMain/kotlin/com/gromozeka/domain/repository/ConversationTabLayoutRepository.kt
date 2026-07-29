package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.User

interface ConversationTabLayoutRepository {
    suspend fun load(userId: User.Id): ConversationTabLayout

    suspend fun loadAll(): Map<User.Id, ConversationTabLayout>

    suspend fun save(userId: User.Id, layout: ConversationTabLayout): ConversationTabLayout
}
