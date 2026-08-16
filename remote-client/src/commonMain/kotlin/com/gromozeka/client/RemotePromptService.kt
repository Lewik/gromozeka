package com.gromozeka.client

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.remote.protocol.CreatePromptRequest
import com.gromozeka.remote.protocol.DeletePromptRequest
import com.gromozeka.remote.protocol.FindPromptRequest
import com.gromozeka.remote.protocol.FindPromptsRequest
import com.gromozeka.remote.protocol.PromptResponse
import com.gromozeka.remote.protocol.PromptsResponse
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.UpdatePromptRequest
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

internal class RemotePromptService(
    private val client: GromozekaWsClient,
) : PromptDomainService {
    override suspend fun findById(id: Prompt.Id): Prompt? =
        client.requestTyped<FindPromptRequest, PromptResponse>(FindPromptRequest(id)).prompt

    override suspend fun findAll(): List<Prompt> =
        client.requestTyped<FindPromptsRequest, PromptsResponse>(FindPromptsRequest()).prompts

    override fun observeAll(): Flow<List<Prompt>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.PROMPTS, load = ::findAll)

    override suspend fun findByProject(projectId: com.gromozeka.domain.model.Project.Id): List<Prompt> =
        client.requestTyped<FindPromptsRequest, PromptsResponse>(FindPromptsRequest(projectId)).prompts

    override fun observeByProject(
        projectId: com.gromozeka.domain.model.Project.Id,
    ): Flow<List<Prompt>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.PROMPTS) { findByProject(projectId) }

    override suspend fun createPrompt(
        projectId: com.gromozeka.domain.model.Project.Id?,
        name: String,
        content: String,
    ): Prompt =
        client.requestTyped<CreatePromptRequest, PromptResponse>(
            CreatePromptRequest(projectId, name, content)
        ).prompt ?: error("Server returned null prompt after create")

    override suspend fun updatePrompt(
        id: Prompt.Id,
        name: String,
        content: String,
    ): Prompt? =
        client.requestTyped<UpdatePromptRequest, PromptResponse>(
            UpdatePromptRequest(id, name, content)
        ).prompt

    override suspend fun deletePrompt(id: Prompt.Id) {
        client.requestTyped<DeletePromptRequest, SavedResponse>(DeletePromptRequest(id))
    }
}
