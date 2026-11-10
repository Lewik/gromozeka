package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.ai.AiAdmissionLease
import com.gromozeka.domain.model.ai.AiAdmissionOperation
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface AiAdmissionCommand {
    @Serializable
    data class Acquire(val operation: AiAdmissionOperation, val workerRequestId: String) : AiAdmissionCommand

    @Serializable
    data class Accept(val operationId: String) : AiAdmissionCommand

    @Serializable
    data class Finish(val operationId: String, val outcome: AiAdmissionOutcome) : AiAdmissionCommand
}

object AiAdmissionGatewayCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(command: AiAdmissionCommand): ByteArray = json.encodeToString(command).encodeToByteArray()
    fun decode(payload: ByteArray): AiAdmissionCommand {
        require(payload.size <= 16 * 1024) { "AI admission request is too large" }
        return json.decodeFromString(payload.decodeToString(throwOnInvalidSequence = true))
    }
    fun encodeLease(lease: AiAdmissionLease): ByteArray = json.encodeToString(lease).encodeToByteArray()
    fun decodeLease(payload: ByteArray): AiAdmissionLease = json.decodeFromString(payload.decodeToString(throwOnInvalidSequence = true))
}
