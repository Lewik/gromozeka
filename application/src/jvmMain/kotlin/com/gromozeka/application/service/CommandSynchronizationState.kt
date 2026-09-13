package com.gromozeka.application.service

import com.gromozeka.domain.model.safeToolOutputText
import com.gromozeka.domain.service.ControlPlaneUnavailableException

internal class CommandSynchronizationState {
    var rejection: String? = null
        private set
    private var retryAtNanos: Long? = null
    private var retryDelayMillis = 1_000L

    fun canAttempt(): Boolean = rejection == null && (retryAtNanos?.let { System.nanoTime() - it >= 0 } ?: true)

    fun succeeded() {
        retryAtNanos = null
        retryDelayMillis = 1_000L
    }

    fun failed(error: Throwable) {
        if (error is ControlPlaneUnavailableException) {
            retryAtNanos = System.nanoTime() + retryDelayMillis * 1_000_000L
            retryDelayMillis = minOf(30_000L, retryDelayMillis * 2)
        } else {
            rejection = "${error::class.simpleName}: ${error.message}".safeToolOutputText()
        }
    }
}
