package com.gromozeka.domain.repository

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserMessageDeliveryMode

interface UserMessageDeliveryPreferenceRepository {
    suspend fun find(userId: User.Id): UserMessageDeliveryMode?
    suspend fun save(userId: User.Id, mode: UserMessageDeliveryMode)
}
