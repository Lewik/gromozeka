package com.gromozeka.application.service

import kotlin.time.Duration.Companion.seconds

internal object ConversationRuntimeTiming {
    val workerRegistrationStaleAfter = 30.seconds
}
