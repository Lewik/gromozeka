package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

data class MobileWorkerContactObservation(
    val requestId: String,
    val workerId: ConversationRuntimeWorkerId,
    val subjectUserId: User.Id,
    val kind: MobileWorkerContactKind,
    val appState: MobileWorkerAppState,
    val appVersion: String?,
    val workerSentAt: Instant?,
    val receivedAt: Instant,
    val eventCount: Int,
    val pendingEventCount: Int?,
) {
    init {
        require(requestId.matches(mobileWorkerRequestIdPattern)) {
            "Mobile Worker request ID must contain 1-128 letters, digits, dots, dashes, or underscores"
        }
        require(appVersion == null || appVersion.isNotBlank() && appVersion.length <= 255) {
            "Mobile Worker version must be non-blank and at most 255 characters"
        }
        require(eventCount >= 0) { "Mobile Worker event count must not be negative" }
        require(pendingEventCount == null || pendingEventCount >= eventCount) {
            "Mobile Worker pending event count must include the submitted events"
        }
    }
}

enum class MobileWorkerContactKind {
    EVENT_BATCH,
    HEARTBEAT,
}

@Serializable
enum class MobileWorkerAppState {
    FOREGROUND,
    BACKGROUND,
    UNKNOWN,
}

private val mobileWorkerRequestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
