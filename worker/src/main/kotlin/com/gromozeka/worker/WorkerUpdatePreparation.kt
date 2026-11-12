package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerUpdateParticipant
import com.gromozeka.worker.runtime.WorkerUpdateGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class WorkerUpdatePreparation(
    private val requests: WorkerUpdateGate,
    private val participants: List<WorkerUpdateParticipant>,
) {
    suspend fun prepare(): Boolean {
        if (!requests.prepare()) return false
        var prepared = false
        try {
            prepared = participants.all { it.prepareForUpdate() }
            return prepared
        } finally {
            if (!prepared) withContext(NonCancellable) { resume() }
        }
    }

    suspend fun resume(): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        try {
            participants.asReversed().forEach {
                try { it.resumeAfterUpdate() }
                catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
        } finally {
            requests.resume()
        }
        failure?.let { throw it }
    }
}
