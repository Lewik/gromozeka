package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.ai.AiAdmissionLease
import com.gromozeka.domain.model.ai.AiAdmissionOperation
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiAdmissionCompletionResult
import com.gromozeka.domain.model.ai.AiHttpQuotaFeedback
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface AiAdmissionCommand {
    @Serializable
    data class Acquire(val operation: AiAdmissionOperation, val workerRequestId: String, val httpRequest: JsonElement? = null) : AiAdmissionCommand

    @Serializable
    data class Accept(val operationId: String) : AiAdmissionCommand

    @Serializable
    data class Finish(val operationId: String, val outcome: AiAdmissionOutcome, val feedback: AiHttpQuotaFeedback? = null) : AiAdmissionCommand
}

object AiAdmissionGatewayCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(command: AiAdmissionCommand): ByteArray = json.encodeToString(command).encodeToByteArray()
    fun decode(payload: ByteArray): AiAdmissionCommand {
        require(payload.size <= 32 * 1024 * 1024) { "AI admission request is too large" }
        return json.decodeFromString(payload.decodeToString(throwOnInvalidSequence = true))
    }
    fun encodeCompletion(result: AiAdmissionCompletionResult): ByteArray = json.encodeToString(result).encodeToByteArray()
    fun decodeCompletion(payload: ByteArray): AiAdmissionCompletionResult = json.decodeFromString(payload.decodeToString(throwOnInvalidSequence = true))
    fun encodeLease(lease: AiAdmissionLease): ByteArray = json.encodeToString(lease).encodeToByteArray()
    fun decodeLease(payload: ByteArray): AiAdmissionLease = json.decodeFromString(payload.decodeToString(throwOnInvalidSequence = true))
}
