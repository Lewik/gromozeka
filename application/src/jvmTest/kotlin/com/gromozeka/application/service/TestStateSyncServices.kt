package com.gromozeka.application.service

import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal fun testConversationRuntimeStateSyncService(
    coordinator: ConversationRuntimeCoordinator,
) = ConversationRuntimeStateSyncApplicationService(
    runtimeCoordinator = coordinator,
    scope = CoroutineScope(Dispatchers.Default),
)

internal class RecordingDeclarativeStateChangePublisher : DeclarativeStateChangePublisher {
    val publishedKeys = mutableListOf<DeclarativeStateKey>()

    override fun publish(vararg keys: DeclarativeStateKey) {
        publishedKeys += keys
    }
}
