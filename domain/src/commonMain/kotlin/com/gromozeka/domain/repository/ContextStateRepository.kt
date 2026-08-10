package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ContextEvent
import com.gromozeka.domain.model.ContextEventAppendResult
import com.gromozeka.domain.model.ContextStateEntry
import com.gromozeka.domain.model.User
import kotlin.time.Instant

interface ContextStateRepository {
    suspend fun append(events: List<ContextEvent>): ContextEventAppendResult

    suspend fun currentState(
        userId: User.Id,
        subject: ContextEvent.Subject? = null,
    ): List<ContextStateEntry>

    suspend fun history(
        userId: User.Id,
        subject: ContextEvent.Subject? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 100,
    ): List<ContextEvent>
}
