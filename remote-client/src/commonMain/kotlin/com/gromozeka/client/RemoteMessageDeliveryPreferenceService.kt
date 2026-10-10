package com.gromozeka.client

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserMessageDeliveryMode
import com.gromozeka.domain.service.CurrentUserMessageDeliveryPreferenceService
import com.gromozeka.remote.protocol.GetMessageDeliveryModeRequest
import com.gromozeka.remote.protocol.MessageDeliveryModeResponse
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import com.gromozeka.remote.protocol.SetMessageDeliveryModeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class RemoteMessageDeliveryPreferenceService(
    private val client: GromozekaWsClient,
    private val currentUserId: User.Id,
    private val scope: CoroutineScope,
) : CurrentUserMessageDeliveryPreferenceService {
    private val current = MutableStateFlow(UserMessageDeliveryMode.STEER)
    override val mode = current.asStateFlow()
    private var syncJob: Job? = null

    private suspend fun read(): UserMessageDeliveryMode =
        client.requestTyped<GetMessageDeliveryModeRequest, MessageDeliveryModeResponse>(GetMessageDeliveryModeRequest).mode

    suspend fun initialize() {
        current.value = read()
        if (syncJob == null) syncJob = scope.launch {
            client.observeDeclarativeState(RemoteDeclarativeStateResource.MESSAGE_DELIVERY_PREFERENCE,
                currentUserId.value, ::read).collect { current.value = it }
        }
    }

    override suspend fun setMode(mode: UserMessageDeliveryMode) {
        client.requestTyped<SetMessageDeliveryModeRequest, MessageDeliveryModeResponse>(SetMessageDeliveryModeRequest(mode))
        current.value = read()
    }
}
