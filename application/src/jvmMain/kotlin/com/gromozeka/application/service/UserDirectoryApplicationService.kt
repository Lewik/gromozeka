package com.gromozeka.application.service

import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.UserDirectoryService
import org.springframework.stereotype.Service

@Service
class UserDirectoryApplicationService(
    private val identityRepository: IdentityRepository,
) : UserDirectoryService {
    override suspend fun findActiveById(id: User.Id): User? =
        identityRepository.findUserById(id)
            ?.takeIf { it.status == User.Status.ACTIVE }

    override suspend fun listActive(): List<User> =
        identityRepository.listUsers()
            .filter { it.status == User.Status.ACTIVE }
}
