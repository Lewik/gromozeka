package com.gromozeka.client

import com.gromozeka.remote.protocol.ClientActivityKind
import com.gromozeka.remote.protocol.ClientPresentationDirective
import kotlinx.coroutines.flow.SharedFlow

class RemoteClientPresentationService internal constructor(
    private val client: GromozekaWsClient,
) {
    val directives: SharedFlow<ClientPresentationDirective> = client.presentationDirectives

    fun reportWindowFocused() {
        client.reportActivity(ClientActivityKind.WINDOW_FOCUSED)
    }

    fun reportUserInteraction() {
        client.reportActivity(ClientActivityKind.USER_INTERACTION)
    }
}
