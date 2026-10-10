package com.gromozeka.application.service

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserMessageDeliveryMode
import com.gromozeka.domain.repository.UserMessageDeliveryPreferenceRepository
import com.gromozeka.domain.service.DeclarativeStateInvalidator
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.UserMessageDeliveryPreferenceService
import org.springframework.stereotype.Service

@Service
class UserMessageDeliveryPreferenceApplicationService(
    private val repository: UserMessageDeliveryPreferenceRepository,
    private val stateInvalidator: DeclarativeStateInvalidator,
) : UserMessageDeliveryPreferenceService {
    override suspend fun get(userId: User.Id): UserMessageDeliveryMode =
        repository.find(userId) ?: UserMessageDeliveryMode.STEER

    override suspend fun set(userId: User.Id, mode: UserMessageDeliveryMode): UserMessageDeliveryMode {
        repository.save(userId, mode)
        stateInvalidator.invalidate(DeclarativeStateKey.messageDeliveryPreference(userId))
        return get(userId)
    }
}
