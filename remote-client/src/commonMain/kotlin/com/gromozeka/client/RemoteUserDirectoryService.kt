package com.gromozeka.client

import com.gromozeka.remote.protocol.ListUserDirectoryRequest
import com.gromozeka.remote.protocol.UserDirectoryEntry
import com.gromozeka.remote.protocol.UserDirectoryResponse

class RemoteUserDirectoryService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun list(): List<UserDirectoryEntry> =
        client.requestTyped<ListUserDirectoryRequest, UserDirectoryResponse>(
            ListUserDirectoryRequest
        ).users
}
