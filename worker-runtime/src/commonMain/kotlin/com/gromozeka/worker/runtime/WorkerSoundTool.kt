package com.gromozeka.worker.runtime

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull

@Serializable
enum class WorkerSoundOutcome { COMPLETED, STOPPED_LOCALLY }

class WorkerSoundController(
    private val output: suspend (durationSeconds: Int, onStarted: () -> Unit) -> Nothing,
    private val onPlayingChanged: suspend (Boolean) -> Unit = {},
) {
    private val playbackLock = Mutex()
    private val stopSignal = MutableStateFlow<CompletableDeferred<Unit>?>(null)

    fun stop(): Boolean = stopSignal.value?.complete(Unit) == true

    suspend fun play(durationSeconds: Int): WorkerSoundOutcome {
        require(durationSeconds in 1..60) { "Sound duration must be between 1 and 60 seconds" }
        require(playbackLock.tryLock()) { "A Worker sound is already playing; it will not be queued or replaced" }
        val stopped = CompletableDeferred<Unit>()
        stopSignal.value = stopped
        try {
            onPlayingChanged(true)
            if (stopped.isCompleted) return WorkerSoundOutcome.STOPPED_LOCALLY
            return coroutineScope {
                val started = CompletableDeferred<Unit>()
                val playback = launch { output(durationSeconds) { check(started.complete(Unit)) { "Sound output signaled startup twice" } } }
                try {
                    val ready = withTimeoutOrNull(5_000) {
                        select {
                            started.onAwait { true }
                            stopped.onAwait { false }
                        }
                    }
                    require(ready != null) { "Device did not start sound within five seconds" }
                    if (!ready || withTimeoutOrNull(durationSeconds * 1_000L) { stopped.await(); true } == true) {
                        WorkerSoundOutcome.STOPPED_LOCALLY
                    } else WorkerSoundOutcome.COMPLETED
                } finally {
                    withContext(NonCancellable) { playback.cancelAndJoin() }
                }
            }
        } finally {
            stopSignal.value = null
            try { withContext(NonCancellable) { onPlayingChanged(false) } }
            finally { playbackLock.unlock() }
        }
    }
}

class WorkerSoundTool(private val controller: WorkerSoundController) : WorkerTool {
    override val descriptor = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "grz_play_loud_sound",
            description = "Play a loud, bounded alert on the selected Worker's built-in speaker, only with local sound opt-in. duration_seconds defaults to 10, maximum 60. Waits until completion or local stop. Concurrent sounds fail instead of queuing. Silent ringer mode is separate from alarm volume; device DND policy, output routing or audio focus can block playback and produce an error. Never promises the person heard it. Delivery TTL and execution timeout belong to the Worker request, not these arguments. Cancellation stops playback; redelivery of the same request does not replay it.",
            inputSchema = """{"type":"object","properties":{"duration_seconds":{"type":"integer","minimum":1,"maximum":60,"default":10}},"additionalProperties":false}""",
        ),
        metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.WORKER,
            requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
            visibleToMemoryPipeline = false,
        ),
    )

    override suspend fun execute(arguments: JsonElement): JsonElement {
        require(arguments is JsonObject && arguments.keys.all { it == "duration_seconds" }) { "Sound expects only duration_seconds" }
        val duration = if ("duration_seconds" in arguments) {
            val value = arguments["duration_seconds"] as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull != null) { "duration_seconds must be an integer" }
            requireNotNull(value.intOrNull)
        } else 10
        return Json.encodeToJsonElement(WorkerSoundResult(controller.play(duration), duration))
    }

    @Serializable
    private data class WorkerSoundResult(val outcome: WorkerSoundOutcome, val requestedDurationSeconds: Int)
}
