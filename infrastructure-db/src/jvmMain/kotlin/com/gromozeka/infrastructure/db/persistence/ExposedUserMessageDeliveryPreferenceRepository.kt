package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserMessageDeliveryMode
import com.gromozeka.domain.repository.UserMessageDeliveryPreferenceRepository
import com.gromozeka.infrastructure.db.persistence.tables.UserMessageDeliveryPreferences
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.stereotype.Service

@Service
class ExposedUserMessageDeliveryPreferenceRepository : UserMessageDeliveryPreferenceRepository {
    override suspend fun find(userId: User.Id): UserMessageDeliveryMode? = dbQuery {
        UserMessageDeliveryPreferences.selectAll()
            .where { UserMessageDeliveryPreferences.userId eq userId.value }
            .singleOrNull()?.let { UserMessageDeliveryMode.valueOf(it[UserMessageDeliveryPreferences.deliveryMode]) }
    }

    override suspend fun save(userId: User.Id, mode: UserMessageDeliveryMode): Unit = dbQuery {
        UserMessageDeliveryPreferences.upsert(UserMessageDeliveryPreferences.userId) {
            it[UserMessageDeliveryPreferences.userId] = userId.value
            it[deliveryMode] = mode.name
        }
    }
}
