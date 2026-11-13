package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiContextMeasurement
import com.gromozeka.domain.service.ResolvedAiRuntime
import klog.KLoggers
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/** Store the execution-side measurement for both local and Server-side continuation estimates. */
internal object AiContextMeasurements {
    private val log = KLoggers.logger(this)

    fun record(runtime: ResolvedAiRuntime, request: AiRuntimeRequest, response: AiRuntimeResponse,
        estimator: AiRequestTokenEstimator = AiRequestTokenEstimate): AiRuntimeResponse {
        val measurement = try {
            AiContextMeasurement.capture(runtime, request, response, estimator)
        } catch (error: SerializationException) {
            log.warn {
                "AI context estimate serialization failed; preserving provider response without calibration: " +
                    "connection=${runtime.connection.id.value}, configuration=${runtime.modelConfiguration.id.value}, " +
                    "error=${error.javaClass.simpleName}"
            }
            return response
        } ?: return response
        return response.copy(messages = response.messages.mapIndexed { index, message ->
            if (index == response.messages.lastIndex) message.copy(metadata = message.metadata +
                (AiContextMeasurement.KEY to Json.encodeToJsonElement(measurement))) else message
        })
    }

    fun calibrate(runtime: ResolvedAiRuntime, request: AiRuntimeRequest, inspection: AiContextInspection,
        estimatorId: String = AiRequestTokenEstimate.id): AiContextInspection {
        if (inspection.precision != AiContextInspection.Precision.ESTIMATE) return inspection
        val measurement = AiContextMeasurement.matching(runtime, request)?.takeIf { it.estimatorId == estimatorId }
            ?: return inspection
        return measurement.estimate(inspection.inputTokens, portable = false)
            ?.copy(nativeCompactionAvailable = inspection.nativeCompactionAvailable) ?: inspection
    }
}
