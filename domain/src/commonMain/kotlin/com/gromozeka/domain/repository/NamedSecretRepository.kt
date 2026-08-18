package com.gromozeka.domain.repository

import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.StoredNamedSecret
import com.gromozeka.domain.model.User

interface NamedSecretRepository {
    suspend fun list(userId: User.Id): List<NamedSecret>

    suspend fun find(
        userId: User.Id,
        name: String,
    ): StoredNamedSecret?

    suspend fun save(
        secret: NamedSecret,
        value: String,
    ): NamedSecret

    suspend fun delete(
        userId: User.Id,
        secretId: NamedSecret.Id,
    ): Boolean
}
