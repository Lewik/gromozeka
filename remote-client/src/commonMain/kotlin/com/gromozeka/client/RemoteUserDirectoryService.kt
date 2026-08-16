package com.gromozeka.client

import com.gromozeka.remote.protocol.ListUserDirectoryRequest
import com.gromozeka.remote.protocol.UserDirectoryEntry
import com.gromozeka.remote.protocol.UserDirectoryResponse
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

class RemoteUserDirectoryService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun list(): List<UserDirectoryEntry> =
        client.requestTyped<ListUserDirectoryRequest, UserDirectoryResponse>(
            ListUserDirectoryRequest
        ).users

    fun observe(): Flow<List<UserDirectoryEntry>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.USER_DIRECTORY, load = ::list)
}
