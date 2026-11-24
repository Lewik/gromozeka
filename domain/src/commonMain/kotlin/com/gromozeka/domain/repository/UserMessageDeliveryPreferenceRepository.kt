package com.gromozeka.domain.repository

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.MessageDelivery

interface UserMessageDeliveryPreferenceRepository {
    suspend fun find(userId: User.Id): MessageDelivery?
    suspend fun save(userId: User.Id, mode: MessageDelivery)
}
