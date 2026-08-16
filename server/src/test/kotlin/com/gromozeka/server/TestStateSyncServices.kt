package com.gromozeka.server

import com.gromozeka.application.service.ConversationRuntimeStateSyncApplicationService
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal fun testConversationRuntimeStateSyncService(
    coordinator: ConversationRuntimeCoordinator,
) = ConversationRuntimeStateSyncApplicationService(
    runtimeCoordinator = coordinator,
    scope = CoroutineScope(Dispatchers.Default),
)
