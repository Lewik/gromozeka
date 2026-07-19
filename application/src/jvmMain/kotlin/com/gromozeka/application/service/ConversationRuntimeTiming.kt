package com.gromozeka.application.service

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal object ConversationRuntimeTiming {
    val eventPublishLeaseDuration = 1.minutes
    val workPublishLeaseDuration = 1.minutes
    val workerRegistrationStaleAfter = 30.seconds
    const val eventOutboxScanIntervalMillis = 2_000L
    const val workOutboxScanIntervalMillis = 500L
    const val workerAvailabilityScanIntervalMillis = 5_000L
    const val controlPollIntervalMillis = 250L
    const val workerHeartbeatIntervalMillis = 5_000L
}
