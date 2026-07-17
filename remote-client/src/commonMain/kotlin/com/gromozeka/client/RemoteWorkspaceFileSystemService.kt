package com.gromozeka.client

import com.gromozeka.domain.model.WorkspaceDirectoryListing
import com.gromozeka.domain.service.WorkspaceFileSystemService
import com.gromozeka.remote.protocol.BrowseWorkspaceRequest
import com.gromozeka.remote.protocol.WorkspaceDirectoryResponse

internal class RemoteWorkspaceFileSystemService(
    private val client: GromozekaWsClient,
) : WorkspaceFileSystemService {
    override suspend fun browse(
        path: String?,
        includeFiles: Boolean,
    ): WorkspaceDirectoryListing =
        client.requestTyped<BrowseWorkspaceRequest, WorkspaceDirectoryResponse>(
            BrowseWorkspaceRequest(path, includeFiles)
        ).listing
}
