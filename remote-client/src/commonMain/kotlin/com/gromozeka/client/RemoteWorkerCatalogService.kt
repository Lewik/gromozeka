package com.gromozeka.client

import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import com.gromozeka.remote.protocol.ListWorkersRequest
import com.gromozeka.remote.protocol.WorkersResponse
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

internal class RemoteWorkerCatalogService(
    private val client: GromozekaWsClient,
) : WorkerCatalogService {
    override suspend fun listWorkers(): List<WorkerCatalogEntry> =
        client.requestTyped<ListWorkersRequest, WorkersResponse>(ListWorkersRequest).workers

    override fun observeWorkers(): Flow<List<WorkerCatalogEntry>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.WORKERS, load = ::listWorkers)
}
