package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.UserMessageDeliveryMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("get_message_delivery_mode")
data object GetMessageDeliveryModeRequest : ClientRequest

@Serializable
@SerialName("set_message_delivery_mode")
data class SetMessageDeliveryModeRequest(val mode: UserMessageDeliveryMode) : ClientRequest

@Serializable
@SerialName("message_delivery_mode")
data class MessageDeliveryModeResponse(val mode: UserMessageDeliveryMode) : ServerResponse
