package com.gromozeka.domain.service

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class ComputerUseDisplayId(val value: String) {
    init {
        require(value.isNotBlank()) { "Computer Use display id must not be blank" }
    }
}

@Serializable
data class ComputerUseDisplay(
    val id: ComputerUseDisplayId,
    val name: String,
    val originX: Int,
    val originY: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val scaleX: Double,
    val scaleY: Double,
    val primary: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Computer Use display name must not be blank" }
        require(logicalWidth > 0 && logicalHeight > 0) { "Computer Use display dimensions must be positive" }
        require(scaleX > 0.0 && scaleY > 0.0) { "Computer Use display scale must be positive" }
    }
}

@Serializable
@JvmInline
value class ComputerUseObservationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Computer Use observation id must not be blank" }
    }
}

/**
 * Self-contained coordinate frame for one screenshot. It deliberately does not
 * claim that the desktop contents are still current when a later action starts.
 */
@Serializable
data class ComputerUseObservationReference(
    val id: ComputerUseObservationId,
    val workerId: ConversationRuntimeWorkerId,
    val workerSessionId: ConversationRuntimeWorkerSessionId,
    val displayId: ComputerUseDisplayId,
    val imageWidth: Int,
    val imageHeight: Int,
    val logicalOriginX: Int,
    val logicalOriginY: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val capturedAt: Instant,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "Computer Use image dimensions must be positive" }
        require(logicalWidth > 0 && logicalHeight > 0) { "Computer Use logical dimensions must be positive" }
    }
}

data class ComputerUseObservation(
    val reference: ComputerUseObservationReference,
    val png: ByteArray,
) {
    init {
        require(png.isNotEmpty()) { "Computer Use observation image must not be empty" }
    }
}

@Serializable
data class ComputerUsePoint(
    val x: Int,
    val y: Int,
) {
    init {
        require(x >= 0 && y >= 0) { "Computer Use coordinates must not be negative" }
    }
}

@Serializable
enum class ComputerUseMouseButton {
    LEFT,
    MIDDLE,
    RIGHT,
}

@Serializable
@JsonClassDiscriminator("actionType")
sealed interface ComputerUseAction {
    @Serializable
    @SerialName("move")
    data class Move(
        val point: ComputerUsePoint,
        val durationMillis: Long = 0,
    ) : ComputerUseAction {
        init {
            require(durationMillis in 0..10_000) { "Pointer move duration must be between 0 and 10000 ms" }
        }
    }

    @Serializable
    @SerialName("click")
    data class Click(
        val point: ComputerUsePoint? = null,
        val button: ComputerUseMouseButton = ComputerUseMouseButton.LEFT,
        val count: Int = 1,
    ) : ComputerUseAction {
        init {
            require(count in 1..3) { "Click count must be between 1 and 3" }
        }
    }

    @Serializable
    @SerialName("drag")
    data class Drag(
        val from: ComputerUsePoint,
        val to: ComputerUsePoint,
        val button: ComputerUseMouseButton = ComputerUseMouseButton.LEFT,
        val durationMillis: Long = 500,
    ) : ComputerUseAction {
        init {
            require(durationMillis in 1..10_000) { "Drag duration must be between 1 and 10000 ms" }
        }
    }

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val deltaX: Int = 0,
        val deltaY: Int,
        val point: ComputerUsePoint? = null,
    ) : ComputerUseAction {
        init {
            require(deltaX != 0 || deltaY != 0) { "Computer Use scroll must move on at least one axis" }
            require(deltaX in -10_000..10_000 && deltaY in -10_000..10_000) {
                "Computer Use scroll delta must be between -10000 and 10000"
            }
        }
    }

    @Serializable
    @SerialName("type_text")
    data class TypeText(
        val text: String,
        val intervalMillis: Long = 0,
    ) : ComputerUseAction {
        init {
            require(text.isNotEmpty()) { "Computer Use text must not be empty" }
            require(text.length <= 32_000) { "Computer Use text must contain at most 32000 characters" }
            require(intervalMillis in 0..2_000) { "Typing interval must be between 0 and 2000 ms" }
        }
    }

    @Serializable
    @SerialName("key_chord")
    data class KeyChord(
        val keys: List<String>,
    ) : ComputerUseAction {
        init {
            require(keys.isNotEmpty()) { "Computer Use key chord must contain at least one key" }
            require(keys.size <= 8) { "Computer Use key chord must contain at most 8 keys" }
            require(keys.all { it.isNotBlank() }) { "Computer Use key names must not be blank" }
        }
    }

    @Serializable
    @SerialName("wait")
    data class Wait(
        val durationMillis: Long,
    ) : ComputerUseAction {
        init {
            require(durationMillis in 1..30_000) { "Computer Use wait must be between 1 and 30000 ms" }
        }
    }
}

interface ComputerUseController {
    val available: Boolean

    val unavailableReason: String?

    suspend fun targets(): List<ComputerUseDisplay>

    suspend fun observe(
        displayId: ComputerUseDisplayId,
        maxLongEdge: Int,
    ): ComputerUseObservation

    suspend fun act(
        observation: ComputerUseObservationReference,
        actions: List<ComputerUseAction>,
        maxLongEdge: Int,
        cancellationCheck: () -> Unit = {},
    ): ComputerUseObservation
}
