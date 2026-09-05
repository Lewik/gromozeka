package com.gromozeka.worker.runtime

import com.gromozeka.remote.protocol.WorkerGatewayMessage
import kotlinx.serialization.Serializable
import kotlin.time.Instant

interface WorkerRequestJournal {
    suspend fun load(): List<WorkerRequestReceipt>
    suspend fun save(receipt: WorkerRequestReceipt)
    suspend fun delete(id: String)
}

@Serializable
data class WorkerRequestReceipt(
    val id: String,
    val fingerprint: String,
    val startDeadline: Instant,
    val state: State,
    val response: WorkerGatewayMessage.Response? = null,
) {
    @Serializable
    enum class State { RECEIVED, RUNNING, COMPLETED, ACKNOWLEDGED }
}
