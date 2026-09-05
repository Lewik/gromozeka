package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlin.time.Instant
import kotlinx.serialization.Serializable

data class WorkerContactObservation(
    val requestId: String,
    val workerId: ConversationRuntimeWorkerId,
    val subjectUserId: User.Id,
    val kind: WorkerContactKind,
    val appState: WorkerAppState,
    val appVersion: String?,
    val workerSentAt: Instant?,
    val receivedAt: Instant,
    val eventCount: Int,
    val pendingEventCount: Int?,
) {
    init {
        require(requestId.matches(workerRequestIdPattern)) {
            "Worker request ID must contain 1-128 letters, digits, dots, dashes, or underscores"
        }
        require(appVersion == null || appVersion.isNotBlank() && appVersion.length <= 255) {
            "Worker version must be non-blank and at most 255 characters"
        }
        require(eventCount >= 0) { "Worker event count must not be negative" }
        require(pendingEventCount == null || pendingEventCount >= eventCount) {
            "Worker pending event count must include the submitted events"
        }
    }
}

enum class WorkerContactKind {
    EVENT_BATCH,
    HEARTBEAT,
}

@Serializable
enum class WorkerAppState {
    FOREGROUND,
    BACKGROUND,
    UNKNOWN,
}

private val workerRequestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
