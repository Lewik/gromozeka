package com.gromozeka.infrastructure.ai.tool.worker

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ComputerUseController
import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseObservation
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ComputerUsePoint
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.worker.ComputerActRequest
import com.gromozeka.domain.tool.worker.ComputerActionKind
import com.gromozeka.domain.tool.worker.ComputerActionRequest
import com.gromozeka.domain.tool.worker.ComputerObserveRequest
import com.gromozeka.domain.tool.worker.ComputerTargetsRequest
import com.gromozeka.domain.tool.worker.GrzComputerActTool
import com.gromozeka.domain.tool.worker.GrzComputerObserveTool
import com.gromozeka.domain.tool.worker.GrzComputerTargetsTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzComputerTargetsToolImpl(
    private val controller: ComputerUseController,
) : GrzComputerTargetsTool {
    override val available: Boolean get() = controller.available

    override fun execute(request: ComputerTargetsRequest, context: ToolExecutionContext?): Map<String, Any> =
        runBlocking {
            mapOf(
                "available" to controller.available,
                "targets" to controller.targets().map { display ->
                    mapOf(
                        "display_id" to display.id.value,
                        "name" to display.name,
                        "origin_x" to display.originX,
                        "origin_y" to display.originY,
                        "logical_width" to display.logicalWidth,
                        "logical_height" to display.logicalHeight,
                        "scale_x" to display.scaleX,
                        "scale_y" to display.scaleY,
                        "primary" to display.primary,
                    )
                },
            )
        }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzComputerObserveToolImpl(
    private val controller: ComputerUseController,
) : GrzComputerObserveTool {
    override val available: Boolean get() = controller.available

    override fun execute(
        request: ComputerObserveRequest,
        context: ToolExecutionContext?,
    ): List<AiToolResult> = runBlocking {
        controller.observe(
            displayId = ComputerUseDisplayId(request.display_id),
            maxLongEdge = request.max_long_edge,
        ).toToolResults(actionsCompleted = false)
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzComputerActToolImpl(
    private val controller: ComputerUseController,
) : GrzComputerActTool {
    override val available: Boolean get() = controller.available

    override fun execute(
        request: ComputerActRequest,
        context: ToolExecutionContext?,
    ): List<AiToolResult> = runBlocking {
        require(request.observation_ref.length <= MAX_OBSERVATION_REFERENCE_LENGTH) {
            "Computer Use observation_ref is too long"
        }
        controller.act(
            observation = ComputerUseObservationReferenceCodec.decode(request.observation_ref),
            actions = request.actions.map(ComputerActionRequest::toDomain),
            maxLongEdge = request.max_long_edge,
            cancellationCheck = context?.cancellationSignal?.let { signal ->
                { signal.throwIfCancellationRequested() }
            } ?: {},
        ).toToolResults(actionsCompleted = true)
    }
}

private fun ComputerActionRequest.toDomain(): ComputerUseAction = when (kind) {
    ComputerActionKind.MOVE -> ComputerUseAction.Move(requiredPoint(), duration_ms)
    ComputerActionKind.CLICK -> ComputerUseAction.Click(optionalPoint(), button, click_count)
    ComputerActionKind.DRAG -> ComputerUseAction.Drag(
        from = requiredPoint(),
        to = ComputerUsePoint(
            requireNotNull(to_x) { "to_x is required for DRAG" },
            requireNotNull(to_y) { "to_y is required for DRAG" },
        ),
        button = button,
        durationMillis = duration_ms.takeIf { it > 0 } ?: 500,
    )
    ComputerActionKind.SCROLL -> ComputerUseAction.Scroll(delta_x, delta_y, optionalPoint())
    ComputerActionKind.TYPE_TEXT -> ComputerUseAction.TypeText(
        text = requireNotNull(text) { "text is required for TYPE_TEXT" },
        intervalMillis = duration_ms,
    )
    ComputerActionKind.KEY_CHORD -> ComputerUseAction.KeyChord(keys)
    ComputerActionKind.WAIT -> ComputerUseAction.Wait(duration_ms)
}

private fun ComputerActionRequest.requiredPoint(): ComputerUsePoint = ComputerUsePoint(
    requireNotNull(x) { "x is required for $kind" },
    requireNotNull(y) { "y is required for $kind" },
)

private fun ComputerActionRequest.optionalPoint(): ComputerUsePoint? {
    val pointX = x
    val pointY = y
    return when {
        pointX == null && pointY == null -> null
        pointX != null && pointY != null -> ComputerUsePoint(pointX, pointY)
        else -> error("x and y must be provided together for $kind")
    }
}

private fun ComputerUseObservation.toToolResults(actionsCompleted: Boolean): List<AiToolResult> = listOf(
    AiToolResult.Text(
        json(
            buildMap {
                put("actions_completed", actionsCompleted)
                put("observation_ref", ComputerUseObservationReferenceCodec.encode(reference))
                put("observation_id", reference.id.value)
                put("worker_id", reference.workerId.value)
                put("display_id", reference.displayId.value)
                put("image_width", reference.imageWidth)
                put("image_height", reference.imageHeight)
                put("captured_at", reference.capturedAt.toString())
                put("freshness", "point_in_time_only")
            }
        )
    ),
    AiToolResult.Binary(
        content = png,
        fileName = "computer-${reference.displayId.value.hashCode().toUInt()}-${reference.id.value}.png",
        mediaType = "image/png",
    ),
)

internal object ComputerUseObservationReferenceCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val signingKey = ByteArray(SIGNING_KEY_BYTES).also(SecureRandom()::nextBytes)

    fun encode(reference: ComputerUseObservationReference): String {
        val payload = json.encodeToString(reference).encodeToByteArray()
        return "${encoder.encodeToString(payload)}.${encoder.encodeToString(sign(payload))}"
    }

    fun decode(value: String): ComputerUseObservationReference = try {
        val parts = value.split('.')
        require(parts.size == 2) { "Computer Use observation_ref must contain a payload and signature" }
        val payload = decoder.decode(parts[0])
        val signature = decoder.decode(parts[1])
        require(MessageDigest.isEqual(signature, sign(payload))) {
            "Computer Use observation_ref signature is invalid"
        }
        json.decodeFromString(payload.decodeToString())
    } catch (error: Throwable) {
        throw IllegalArgumentException("Invalid Computer Use observation_ref", error)
    }

    private fun sign(payload: ByteArray): ByteArray = Mac.getInstance(SIGNING_ALGORITHM).run {
        init(SecretKeySpec(signingKey, SIGNING_ALGORITHM))
        doFinal(payload)
    }
}

private fun json(value: Any): String = computerUseObjectMapper.writeValueAsString(value)

private val computerUseObjectMapper = jacksonObjectMapper().findAndRegisterModules()

private const val MAX_OBSERVATION_REFERENCE_LENGTH = 4_096
private const val SIGNING_KEY_BYTES = 32
private const val SIGNING_ALGORITHM = "HmacSHA256"
