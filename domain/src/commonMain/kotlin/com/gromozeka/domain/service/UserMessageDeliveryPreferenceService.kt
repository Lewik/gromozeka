package com.gromozeka.domain.service

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.MessageDelivery
import kotlinx.coroutines.flow.StateFlow

/** Server-owned, keyed by authenticated user rather than the shared legacy UserProfile. */
interface UserMessageDeliveryPreferenceService {
    suspend fun get(userId: User.Id): MessageDelivery
    suspend fun set(userId: User.Id, mode: MessageDelivery): MessageDelivery
}

/** Client projection of its own authenticated user's preference. */
interface CurrentUserMessageDeliveryPreferenceService {
    val mode: StateFlow<MessageDelivery>
    suspend fun setMode(mode: MessageDelivery)
}
