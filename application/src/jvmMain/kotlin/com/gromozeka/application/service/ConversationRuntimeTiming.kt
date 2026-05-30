package com.gromozeka.application.service

import kotlin.time.Duration.Companion.minutes

internal object ConversationRuntimeTiming {
    val eventPublishLeaseDuration = 1.minutes
    val workPublishLeaseDuration = 1.minutes
    const val eventOutboxScanIntervalMillis = 2_000L
    const val workOutboxScanIntervalMillis = 500L
    const val controlPollIntervalMillis = 250L
}
