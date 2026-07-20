package com.gromozeka.client

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.remote.protocol.CreateProjectPromptRequest
import com.gromozeka.remote.protocol.FindPromptRequest
import com.gromozeka.remote.protocol.FindPromptsRequest
import com.gromozeka.remote.protocol.PromptResponse
import com.gromozeka.remote.protocol.PromptsResponse

internal class RemotePromptService(
    private val client: GromozekaWsClient,
) : PromptDomainService {
    override suspend fun findById(id: Prompt.Id): Prompt? =
        client.requestTyped<FindPromptRequest, PromptResponse>(FindPromptRequest(id)).prompt

    override suspend fun findAll(): List<Prompt> =
        client.requestTyped<FindPromptsRequest, PromptsResponse>(FindPromptsRequest()).prompts

    override suspend fun findByProject(projectId: com.gromozeka.domain.model.Project.Id): List<Prompt> =
        client.requestTyped<FindPromptsRequest, PromptsResponse>(FindPromptsRequest(projectId)).prompts

    override suspend fun createProjectPrompt(
        projectId: com.gromozeka.domain.model.Project.Id,
        name: String,
        content: String,
    ): Prompt =
        client.requestTyped<CreateProjectPromptRequest, PromptResponse>(
            CreateProjectPromptRequest(projectId, name, content)
        ).prompt ?: error("Server returned null prompt after create")

}
