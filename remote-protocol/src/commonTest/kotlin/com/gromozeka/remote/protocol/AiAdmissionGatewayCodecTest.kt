package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.ai.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class AiAdmissionGatewayCodecTest {
    @Test fun preparedJsonAndRawFeedbackRemainExactAcrossGateway() {
        val operation = AiAdmissionOperation("id", AiAdmissionTarget.ModelConfiguration(AiModelConfiguration.Id("model"), AiConnection.Id("connection")), "TEST")
        val request = Json.parseToJsonElement("""{"model":"account/model","input":"😀 private synthetic input"}""")
        val acquire = AiAdmissionCommand.Acquire(operation, "root-request", request)
        assertEquals(acquire, AiAdmissionGatewayCodec.decode(AiAdmissionGatewayCodec.encode(acquire)))
        val feedback = AiHttpQuotaFeedback(200, mapOf("Custom-Quota" to listOf("-20")), Json.parseToJsonElement("""{"remaining":-20}"""))
        val finish = AiAdmissionCommand.Finish(operation.id, AiAdmissionOutcome.SUCCEEDED, feedback)
        assertEquals(finish, AiAdmissionGatewayCodec.decode(AiAdmissionGatewayCodec.encode(finish)))
        val lease = AiAdmissionLease(operation.copy(tpm = AiTpmReservation(AiTpmGroup.Id("quota"), 123,
            AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("Custom-Quota")), 10, 2)), AiConcurrencyGroup.DEFAULT_ID,
            Instant.fromEpochMilliseconds(0), "worker", "root-request")
        assertEquals(lease, AiAdmissionGatewayCodec.decodeLease(AiAdmissionGatewayCodec.encodeLease(lease)))
        val result = AiAdmissionCompletionResult("Invalid quota feedback")
        assertEquals(result, AiAdmissionGatewayCodec.decodeCompletion(AiAdmissionGatewayCodec.encodeCompletion(result)))
    }

    @Test fun protocolRejectsMalformedAndOversizedPayloadsRatherThanIgnoringFields() {
        assertFailsWith<IllegalArgumentException> { AiAdmissionGatewayCodec.decode(ByteArray(32 * 1024 * 1024 + 1)) }
        assertFailsWith<IllegalArgumentException> { AiAdmissionGatewayCodec.decode("{}".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { AiAdmissionGatewayCodec.decodeCompletion("{\"unexpected\":1}".encodeToByteArray()) }
    }
}
