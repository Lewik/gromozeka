package com.gromozeka.remote.protocol

import com.gromozeka.domain.slot.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("list_slots")
data object ListSlotsRequest : ClientRequest

@Serializable @SerialName("prepare_slot_reclaim")
data class PrepareSlotReclaimRequest(val leaseId: String) : ClientRequest

/** Authenticated native-client operation; intentionally not exposed through an AI tool. */
@Serializable @SerialName("confirm_slot_reclaim")
data class ConfirmSlotReclaimRequest(val leaseId: String, val confirmationId: String) : ClientRequest

@Serializable @SerialName("cancel_slot_request")
data class CancelSlotRequest(val requestId: String) : ClientRequest

@Serializable @SerialName("slots")
data class SlotsResponse(val slots: List<SlotView>) : ServerResponse

@Serializable @SerialName("slot_lease")
data class SlotLeaseResponse(val lease: SlotLease) : ServerResponse

@Serializable @SerialName("slot_request")
data class SlotRequestResponse(val request: SlotRequest) : ServerResponse

@Serializable @SerialName("slots")
data object SlotsStateQuery : RemoteStateSyncQuery

@Serializable @SerialName("slots")
data class SlotsStatePayload(val slots: List<SlotView>) : RemoteStateSyncPayload
