package com.gromozeka.domain.service

import com.gromozeka.domain.model.NamedSecret

interface CurrentUserNamedSecretService {
    suspend fun list(): List<NamedSecret>

    suspend fun save(
        name: String,
        description: String,
        value: String,
    ): NamedSecret

    suspend fun delete(secretId: NamedSecret.Id): Boolean
}
