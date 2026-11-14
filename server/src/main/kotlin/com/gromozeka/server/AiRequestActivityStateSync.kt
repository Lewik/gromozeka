package com.gromozeka.server

import com.gromozeka.application.service.ServerAiCallAdmission
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AiRequestActivitySnapshot
import com.gromozeka.domain.service.AiRequestActivityStateSyncService
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.statesync.StateSyncSource
import com.gromozeka.statesync.StateSyncSnapshot
import com.gromozeka.statesync.StateSyncSubscription
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class DefaultAiRequestActivityStateSyncService(
    admission: ServerAiCallAdmission,
    @param:Qualifier("applicationScope") scope: CoroutineScope,
) : AiRequestActivityStateSyncService {
    private val source = StateSyncSource<Conversation.Id, AiRequestActivitySnapshot>(
        scope = scope,
        sourceEpoch = uuid7(),
        loader = { admission.activity.value.forConversation(it) },
    )
    private val changes = scope.launch {
        admission.activity.collect { source.invalidateAll() }
    }

    override suspend fun subscribe(key: Conversation.Id): StateSyncSubscription<Conversation.Id, AiRequestActivitySnapshot> =
        source.subscribe(key)

    override suspend fun snapshot(key: Conversation.Id): StateSyncSnapshot<Conversation.Id, AiRequestActivitySnapshot> =
        source.snapshot(key)

    override suspend fun invalidate(key: Conversation.Id) = source.invalidate(key)

    @PreDestroy
    fun close() { changes.cancel() }
}
