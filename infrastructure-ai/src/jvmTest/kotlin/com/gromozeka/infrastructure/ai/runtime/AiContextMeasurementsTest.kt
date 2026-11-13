package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.AiRuntime
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class AiContextMeasurementsTest {
    private val runtime = ResolvedAiRuntime(
        AiConnection.OpenAiCompatible(AiConnection.Id("gateway"), "Gateway", baseUrl = "https://gateway.example/api/llm"),
        AiModelConfiguration(AiModelConfiguration.Id("configuration"), AiConnection.Id("gateway"), "model", "Model"),
        AiModelSpec("model", AiProvider.CUSTOM, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(100_000, 32_000))),
    )
    private val initial = AiRuntimeRequest(listOf("System instructions"), listOf(user("question", "Question")), options = AiRuntimeOptions(
        toolContext = mapOf("threadId" to "thread"), usagePurpose = "CONVERSATION"))
    private val response = AiRuntimeResponse(
        messages = listOf(AiAssistantMessage(content = listOf(
            Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Answer")),
        ))),
        usage = AiUsage(promptTokens = 9000, completionTokens = 8000),
        contextUsage = AiContextUsage(100, 20),
    )

    @Test fun lastContextMeasurementNotCumulativeBillingCalibratesTheNextRequest() {
        val next = initial.copy(messages = initial.messages + persisted(AiContextMeasurements.record(runtime, initial, response)))
        val inspected = AiContextMeasurements.calibrate(runtime, next, estimate(next))
        assertEquals(120, inspected.inputTokens)
        assertEquals(AiContextInspection.Precision.USAGE_CALIBRATED_ESTIMATE, inspected.precision)
        val grown = next.copy(systemPrompts = listOf("A different longer system prompt with more instructions"),
            messages = next.messages + user("next", "New user input"))
        assertEquals(120 + AiRequestTokenEstimate.input(grown) - AiRequestTokenEstimate.input(next),
            AiContextMeasurements.calibrate(runtime, grown, estimate(grown)).inputTokens)
    }

    @Test fun modelThreadAndSourceChangesDiscardTheOldCalibration() {
        val next = initial.copy(messages = initial.messages + persisted(AiContextMeasurements.record(runtime, initial, response)))
        val newThread = next.copy(options = next.options.copy(toolContext = mapOf("threadId" to "new-thread")))
        assertEquals(estimate(newThread), AiContextMeasurements.calibrate(runtime, newThread, estimate(newThread)))
        val newModel = runtime.copy(modelConfiguration = runtime.modelConfiguration.copy(providerModelId = "other"),
            modelSpec = runtime.modelSpec.copy(id = "other"))
        assertEquals(estimate(next), AiContextMeasurements.calibrate(newModel, next, estimate(next)))
        val changed = next.copy(messages = listOf(user("different-source", "Question")) + next.messages.drop(1))
        assertEquals(estimate(changed), AiContextMeasurements.calibrate(runtime, changed, estimate(changed)))
    }

    @Test fun missingOutputUsageIsNotZeroAndHelpersDoNotOverwriteConversationMeasurements() {
        for (candidate in listOf(response.copy(contextUsage = null), response.copy(contextUsage = AiContextUsage(100)))) {
            assertEquals(candidate, AiContextMeasurements.record(runtime, initial, candidate))
        }
        val helper = initial.copy(options = initial.options.copy(usagePurpose = "MESSAGE_SQUASH"))
        assertEquals(response, AiContextMeasurements.record(runtime, helper, response))
    }

    @Test fun oldOrDifferentEstimatorBaselinesAreDiscardedWithoutRewritingHistory() {
        val stored = persisted(AiContextMeasurements.record(runtime, initial, response))
        val measurement = stored.providerMetadata.getValue("gromozekaContextMeasurement").jsonObject
        for (modified in listOf(JsonObject(measurement - "estimatorId" - "version"),
            JsonObject(measurement + ("estimatorId" to JsonPrimitive("different-projection"))))) {
            val source = stored.copy(providerMetadata = JsonObject(stored.providerMetadata + ("gromozekaContextMeasurement" to modified)))
            val next = initial.copy(messages = initial.messages + source)
            assertEquals(estimate(next), AiContextMeasurements.calibrate(runtime, next, estimate(next)))
            assertEquals(modified, source.providerMetadata["gromozekaContextMeasurement"])
        }
    }

    @Test fun modelDefaultsReachBothInspectionAndCalibrationEstimator() = runBlocking {
        val defaults = AiModelConfiguration.DefaultParameters(maxOutputTokens = 42)
        var estimates = 0
        val backend = object : AiRuntime {
            override val tokenEstimator = object : AiRequestTokenEstimator {
                override val id = "configured-estimator"
                override fun input(request: AiRuntimeRequest): Int {
                    assertEquals(42, request.options.maxOutputTokens)
                    estimates++
                    return AiRequestTokenEstimate.input(request)
                }
                override fun output(messages: List<AiAssistantMessage>): Int = AiRequestTokenEstimate.output(messages)
            }
            override suspend fun call(request: AiRuntimeRequest) = response
            override fun stream(request: AiRuntimeRequest) = flowOf(response)
        }
        val wrapped = AiInputValidatingRuntime(ModelDefaultAiRuntime(backend, defaults), runtime)
        val result = wrapped.call(initial)
        val next = initial.copy(messages = initial.messages + persisted(result))
        assertEquals(120, wrapped.inspectContext(next).inputTokens)
        assertEquals(2, estimates)
        assertEquals("configured-estimator", result.messages.single().metadata.getValue("gromozekaContextMeasurement")
            .let { it as JsonObject }.getValue("estimatorId").jsonPrimitive.content)
    }

    @Test fun providerCountNeverGetsAdjustedByAnOldEstimate() {
        val next = initial.copy(messages = initial.messages + persisted(AiContextMeasurements.record(runtime, initial, response)))
        val measured = AiContextInspection(42, AiContextInspection.Precision.PROVIDER_COUNT)
        assertEquals(measured, AiContextMeasurements.calibrate(runtime, next, measured))
    }

    @Test fun incompatibleEstimatorScaleDiscardsCalibrationInsteadOfInventingZero() {
        val largePrompt = initial.copy(systemPrompts = listOf("Repeated schema ".repeat(2000)))
        val recorded = AiContextMeasurements.record(runtime, largePrompt, response)
        val smaller = initial.copy(systemPrompts = emptyList(), messages = initial.messages + persisted(recorded))
        val inspection = AiContextMeasurements.calibrate(runtime, smaller, estimate(smaller))
        assertEquals(estimate(smaller), inspection)
        assertTrue(inspection.inputTokens > 0)
    }

    @Test fun imageAndDocumentRequestsPreserveCompletedAndStreamedProviderResponses() = runBlocking {
        val media = listOf(
            Conversation.Message.ContentItem.ImageItem(
                Conversation.Message.ImageSource.Base64ImageSource("aW1hZ2U=", "image/jpeg")),
            Conversation.Message.ContentItem.DocumentItem(
                Conversation.Message.DocumentSource.Base64DocumentSource("cGRm", "application/pdf", "document.pdf")),
        )
        val request = initial.copy(messages = listOf(initial.messages.single().copy(content = media)))
        val capable = runtime.copy(modelSpec = runtime.modelSpec.copy(capabilities = runtime.modelSpec.capabilities +
            setOf(AiModelCapability.IMAGE_INPUT, AiModelCapability.DOCUMENT_INPUT)))
        var calls = 0
        val backend = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                calls++
                assertEquals(media, request.messages.single().content)
                return response
            }
            override fun stream(request: AiRuntimeRequest) = flowOf(response)
        }
        val wrapped = AiInputValidatingRuntime(backend, capable)
        for (received in listOf(wrapped.call(request), wrapped.stream(request).single())) {
            assertEquals(response.messages.single().content, received.messages.single().content)
            assertEquals(response.usage, received.usage)
            assertEquals(response.contextUsage, received.contextUsage)
            assertEquals(response.outcome, received.outcome)
            assertNotNull(received.messages.single().metadata["gromozekaContextMeasurement"])
        }
        assertEquals(1, calls)
    }

    @Test fun failedAuxiliaryEstimatePreservesTheProviderResponse() {
        val invalidNumber = Conversation.Message.ContentItem.ToolCall(
            Conversation.Message.ContentItem.ToolCall.Id("call"),
            Conversation.Message.ContentItem.ToolCall.Data("tool", JsonPrimitive(Double.NaN)),
        )
        val unencodable = initial.copy(messages = listOf(initial.messages.single().copy(content = listOf(invalidNumber))))
        assertFailsWith<SerializationException> { AiRequestTokenEstimate.input(unencodable) }
        assertSame(response, AiContextMeasurements.record(runtime, unencodable, response))
    }

    @Test fun editsToCoveredInputOrOutputInvalidateEvenWithTheSameIds() {
        val stored = persisted(AiContextMeasurements.record(runtime, initial, response))
        val next = initial.copy(messages = initial.messages + stored)
        for (edited in listOf(
            next.copy(messages = listOf(user("question", "Q"), stored)),
            next.copy(messages = initial.messages + stored.copy(content = listOf(Conversation.Message.ContentItem.UserMessage("Edited output")))),
            next.copy(messages = listOf(user("inserted", "Inserted")) + next.messages),
        )) assertEquals(estimate(edited), AiContextMeasurements.calibrate(runtime, edited, estimate(edited)))
    }

    @Test fun damagedCurrentMetadataIsNotSilentlyTreatedAsLegacy() {
        val stored = persisted(AiContextMeasurements.record(runtime, initial, response))
        val measurement = stored.providerMetadata.getValue("gromozekaContextMeasurement").jsonObject
        val broken = stored.copy(providerMetadata = JsonObject(stored.providerMetadata +
            ("gromozekaContextMeasurement" to JsonObject(measurement - "estimatorId"))))
        val next = initial.copy(messages = initial.messages + broken)
        assertFailsWith<SerializationException> { AiContextMeasurements.calibrate(runtime, next, estimate(next)) }
    }

    @Test fun ordinaryUsageSupportsServerSidePortableInspectionWithoutCallingTheRuntime() = runBlocking {
        var calls = 0
        val backend = object : AiRuntime {
            override suspend fun inspectContext(request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection = error("No preflight needed")
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse { calls++; return response }
            override fun stream(request: AiRuntimeRequest) = flowOf(response)
        }
        val wrapped = AiInputValidatingRuntime(backend, runtime)
        val received = wrapped.call(initial)
        val next = initial.copy(messages = initial.messages + persisted(received))
        val measured = com.gromozeka.domain.service.AiContextMeasurement.portableInspection(runtime, next)
        assertEquals(120, measured?.inputTokens)
        assertEquals(1, calls)
        val changed = next.copy(options = next.options.copy(toolContext = mapOf("threadId" to "fork")))
        assertNull(com.gromozeka.domain.service.AiContextMeasurement.portableInspection(runtime, changed))
    }

    private fun estimate(request: AiRuntimeRequest) = AiContextInspection(AiRequestTokenEstimate.input(request), AiContextInspection.Precision.ESTIMATE)
    private fun persisted(response: AiRuntimeResponse) = user("answer", "").copy(role = Conversation.Message.Role.ASSISTANT,
        content = response.messages.single().content,
        providerMetadata = JsonObject(response.messages.single().metadata.mapValues { (_, value) -> when (value) {
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            null -> JsonNull
            else -> error("Unexpected fixture metadata")
        } }))
    private fun user(id: String, text: String) = Conversation.Message(id = Conversation.Message.Id(id), conversationId = Conversation.Id("conversation"),
        role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage(text)), createdAt = Instant.fromEpochMilliseconds(0))
}
