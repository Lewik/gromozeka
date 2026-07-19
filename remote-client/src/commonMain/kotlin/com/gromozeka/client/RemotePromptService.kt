package com.gromozeka.client

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.remote.protocol.CopyBuiltinPromptToUserRequest
import com.gromozeka.remote.protocol.CreateEnvironmentPromptRequest
import com.gromozeka.remote.protocol.FindPromptRequest
import com.gromozeka.remote.protocol.FindPromptsRequest
import com.gromozeka.remote.protocol.ImportAllClaudeMdRequest
import com.gromozeka.remote.protocol.OperationResultResponse
import com.gromozeka.remote.protocol.PromptResponse
import com.gromozeka.remote.protocol.PromptsResponse
import com.gromozeka.remote.protocol.RefreshPromptsRequest
import com.gromozeka.remote.protocol.ResetAllBuiltinPromptsRequest
import com.gromozeka.remote.protocol.SavedResponse

internal class RemotePromptService(
    private val client: GromozekaWsClient,
) : PromptDomainService {
    override suspend fun findById(id: Prompt.Id): Prompt? =
        client.requestTyped<FindPromptRequest, PromptResponse>(FindPromptRequest(id)).prompt

    override suspend fun findAll(): List<Prompt> =
        client.requestTyped<FindPromptsRequest, PromptsResponse>(FindPromptsRequest).prompts

    override suspend fun refresh() {
        client.requestTyped<RefreshPromptsRequest, SavedResponse>(RefreshPromptsRequest)
    }

    override suspend fun createEnvironmentPrompt(name: String, content: String): Prompt =
        client.requestTyped<CreateEnvironmentPromptRequest, PromptResponse>(
            CreateEnvironmentPromptRequest(name, content)
        ).prompt ?: error("Server returned null prompt after create")

    override suspend fun copyBuiltinPromptToUser(id: Prompt.Id): Result<Unit> =
        client.requestTyped<CopyBuiltinPromptToUserRequest, OperationResultResponse>(
            CopyBuiltinPromptToUserRequest(id)
        ).toUnitResult()

    override suspend fun resetAllBuiltinPrompts(): Result<Int> =
        client.requestTyped<ResetAllBuiltinPromptsRequest, OperationResultResponse>(ResetAllBuiltinPromptsRequest)
            .toCountResult()

    override suspend fun importAllClaudeMd(): Result<Int> =
        client.requestTyped<ImportAllClaudeMdRequest, OperationResultResponse>(ImportAllClaudeMdRequest).toCountResult()

    private fun OperationResultResponse.toUnitResult(): Result<Unit> =
        if (success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(error ?: "Remote operation failed"))
        }

    private fun OperationResultResponse.toCountResult(): Result<Int> =
        if (success) {
            Result.success(count ?: 0)
        } else {
            Result.failure(IllegalStateException(error ?: "Remote operation failed"))
        }
}
