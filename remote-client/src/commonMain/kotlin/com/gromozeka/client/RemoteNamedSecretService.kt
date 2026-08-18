package com.gromozeka.client

import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.service.CurrentUserNamedSecretService
import com.gromozeka.remote.protocol.DeleteNamedSecretRequest
import com.gromozeka.remote.protocol.ListNamedSecretsRequest
import com.gromozeka.remote.protocol.NamedSecretDeletedResponse
import com.gromozeka.remote.protocol.NamedSecretResponse
import com.gromozeka.remote.protocol.NamedSecretsResponse
import com.gromozeka.remote.protocol.SaveNamedSecretRequest

internal class RemoteNamedSecretService(
    private val client: GromozekaWsClient,
) : CurrentUserNamedSecretService {
    override suspend fun list(): List<NamedSecret> =
        client.requestTyped<ListNamedSecretsRequest, NamedSecretsResponse>(ListNamedSecretsRequest).secrets

    override suspend fun save(name: String, description: String, value: String): NamedSecret =
        client.requestTyped<SaveNamedSecretRequest, NamedSecretResponse>(
            SaveNamedSecretRequest(name, description, value)
        ).secret

    override suspend fun delete(secretId: NamedSecret.Id): Boolean =
        client.requestTyped<DeleteNamedSecretRequest, NamedSecretDeletedResponse>(
            DeleteNamedSecretRequest(secretId)
        ).deleted
}
