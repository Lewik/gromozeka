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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import kotlin.time.Duration.Companion.minutes

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
    private val observationReferences: ComputerUseObservationReferenceStore,
) : GrzComputerObserveTool {
    override val available: Boolean get() = controller.available

    override fun execute(
        request: ComputerObserveRequest,
        context: ToolExecutionContext?,
    ): List<AiToolResult> = runBlocking {
        controller.observe(
            displayId = ComputerUseDisplayId(request.display_id),
            maxLongEdge = request.max_long_edge,
        ).toToolResults(observationReferences, actionsCompleted = false)
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzComputerActToolImpl(
    private val controller: ComputerUseController,
    private val observationReferences: ComputerUseObservationReferenceStore,
) : GrzComputerActTool {
    override val available: Boolean get() = controller.available

    override fun execute(
        request: ComputerActRequest,
        context: ToolExecutionContext?,
    ): List<AiToolResult> = runBlocking {
        val actions = request.actions.map(ComputerActionRequest::toDomain)
        controller.act(
            observation = observationReferences.consume(request.observation_ref),
            actions = actions,
            maxLongEdge = request.max_long_edge,
            cancellationCheck = context?.cancellationSignal?.let { signal ->
                { signal.throwIfCancellationRequested() }
            } ?: {},
        ).toToolResults(observationReferences, actionsCompleted = true)
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

private fun ComputerUseObservation.toToolResults(
    observationReferences: ComputerUseObservationReferenceStore,
    actionsCompleted: Boolean,
): List<AiToolResult> = listOf(
    AiToolResult.Text(
        json(
            buildMap {
                put("actions_completed", actionsCompleted)
                put("observation_ref", observationReferences.register(reference))
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

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class ComputerUseObservationReferenceStore private constructor(
    private val maxEntries: Int,
    private val ttlNanos: Long,
    private val nanoTime: () -> Long,
) {
    init {
        require(maxEntries > 0) { "Computer Use observation reference capacity must be positive" }
        require(ttlNanos > 0) { "Computer Use observation reference TTL must be positive" }
    }

    constructor() : this(
        maxEntries = DEFAULT_MAX_ENTRIES,
        ttlNanos = DEFAULT_TTL.inWholeNanoseconds,
        nanoTime = System::nanoTime,
    )

    private data class Entry(
        val reference: ComputerUseObservationReference,
        val expiresAtNanos: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val random = SecureRandom()

    @Synchronized
    fun register(reference: ComputerUseObservationReference): String {
        removeExpired()
        while (entries.size >= maxEntries) {
            entries.remove(entries.keys.first())
        }
        val token = generateSequence(::newToken).first { it !in entries }
        entries[token] = Entry(reference, nanoTime() + ttlNanos)
        return token
    }

    @Synchronized
    fun consume(value: String): ComputerUseObservationReference {
        require(REFERENCE_PATTERN.matches(value)) {
            "Computer Use observation_ref has an invalid format; capture a fresh observation"
        }
        removeExpired()
        return entries.remove(value)?.reference
            ?: throw IllegalArgumentException(
                "Computer Use observation_ref is unknown, expired, or already used; capture a fresh observation"
            )
    }

    private fun newToken(): String = buildString {
        append(REFERENCE_PREFIX)
        append(encoder.encodeToString(ByteArray(REFERENCE_RANDOM_BYTES).also(random::nextBytes)))
    }

    private fun removeExpired() {
        val now = nanoTime()
        entries.entries.removeIf { it.value.expiresAtNanos <= now }
    }

    internal companion object {
        fun forTesting(
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
            ttlNanos: Long = DEFAULT_TTL.inWholeNanoseconds,
            nanoTime: () -> Long = System::nanoTime,
        ): ComputerUseObservationReferenceStore =
            ComputerUseObservationReferenceStore(maxEntries, ttlNanos, nanoTime)

        private val DEFAULT_TTL = 5.minutes
        private const val DEFAULT_MAX_ENTRIES = 64
        private const val REFERENCE_PREFIX = "cu_"
        private const val REFERENCE_RANDOM_BYTES = 16
        private val REFERENCE_PATTERN = Regex("^cu_[A-Za-z0-9_-]{22}$")
    }
}

private fun json(value: Any): String = computerUseObjectMapper.writeValueAsString(value)

private val computerUseObjectMapper = jacksonObjectMapper().findAndRegisterModules()
