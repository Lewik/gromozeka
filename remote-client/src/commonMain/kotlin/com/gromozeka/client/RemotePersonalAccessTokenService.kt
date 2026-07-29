package com.gromozeka.client

import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.remote.protocol.CreatePersonalAccessTokenRequest
import com.gromozeka.remote.protocol.IssuedPersonalAccessTokenResponse
import com.gromozeka.remote.protocol.ListPersonalAccessTokensRequest
import com.gromozeka.remote.protocol.PersonalAccessTokenRevokedResponse
import com.gromozeka.remote.protocol.PersonalAccessTokenView
import com.gromozeka.remote.protocol.PersonalAccessTokensResponse
import com.gromozeka.remote.protocol.RevokePersonalAccessTokenRequest

class RemotePersonalAccessTokenService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun list(): List<PersonalAccessTokenView> =
        client.requestTyped<ListPersonalAccessTokensRequest, PersonalAccessTokensResponse>(
            ListPersonalAccessTokensRequest
        ).tokens

    suspend fun create(
        name: String,
        scopes: Set<PersonalAccessToken.Scope>,
        expiresInDays: Int?,
    ): IssuedPersonalAccessTokenResponse =
        client.requestTyped<CreatePersonalAccessTokenRequest, IssuedPersonalAccessTokenResponse>(
            CreatePersonalAccessTokenRequest(
                name = name,
                scopes = scopes,
                expiresInDays = expiresInDays,
            )
        )

    suspend fun revoke(tokenId: PersonalAccessToken.Id): Boolean =
        client.requestTyped<RevokePersonalAccessTokenRequest, PersonalAccessTokenRevokedResponse>(
            RevokePersonalAccessTokenRequest(tokenId)
        ).revoked
}
