package com.gromozeka.client

import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import com.gromozeka.remote.protocol.ListWorkersRequest
import com.gromozeka.remote.protocol.WorkersResponse

internal class RemoteWorkerCatalogService(
    private val client: GromozekaWsClient,
) : WorkerCatalogService {
    override suspend fun listWorkers(): List<WorkerCatalogEntry> =
        client.requestTyped<ListWorkersRequest, WorkersResponse>(ListWorkersRequest).workers
}
