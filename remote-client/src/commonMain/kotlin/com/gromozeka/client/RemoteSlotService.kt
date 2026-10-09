package com.gromozeka.client

import com.gromozeka.domain.slot.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RemoteSlotService(private val client: GromozekaWsClient) : SlotService {
    override fun observe(): Flow<List<SlotView>> = client.observeState(SlotsStateQuery).map {
        (it.value as SlotsStatePayload).slots
    }
    override suspend fun list(): List<SlotView> = client.requestTyped<ListSlotsRequest, SlotsResponse>(ListSlotsRequest).slots
    override suspend fun prepareReclaim(leaseId: String): SlotLease =
        client.requestTyped<PrepareSlotReclaimRequest, SlotLeaseResponse>(PrepareSlotReclaimRequest(leaseId)).lease
    override suspend fun confirmReclaim(leaseId: String, confirmationId: String): SlotLease =
        client.requestTyped<ConfirmSlotReclaimRequest, SlotLeaseResponse>(ConfirmSlotReclaimRequest(leaseId, confirmationId)).lease
    override suspend fun cancelRequest(requestId: String): SlotRequest =
        client.requestTyped<CancelSlotRequest, SlotRequestResponse>(CancelSlotRequest(requestId)).request
}
