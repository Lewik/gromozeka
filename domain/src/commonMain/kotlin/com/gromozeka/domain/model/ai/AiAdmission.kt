package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
sealed interface AiAdmissionTarget {
    @Serializable
    data class Connection(val id: AiConnection.Id) : AiAdmissionTarget

    @Serializable
    data class ModelConfiguration(val id: AiModelConfiguration.Id, val connectionId: AiConnection.Id) : AiAdmissionTarget

    @Serializable
    data object LocalInference : AiAdmissionTarget
}

@Serializable
data class AiAdmissionOperation(
    val id: String,
    val target: AiAdmissionTarget,
    val purpose: String,
    val deadline: Instant? = null,
    val backgroundSessionId: String? = null,
    val tpm: AiTpmReservation? = null,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Invalid AI admission operation id" }
        require(purpose.matches(Regex("[A-Za-z0-9_:-]{1,80}"))) { "Invalid AI admission purpose" }
        require(backgroundSessionId == null || backgroundSessionId.matches(Regex("[A-Za-z0-9-]{1,128}"))) {
            "Invalid AI background session id"
        }
    }
}

@Serializable
data class AiAdmissionLease(
    val operation: AiAdmissionOperation,
    val groupId: AiConcurrencyGroup.Id,
    val acquiredAt: Instant,
    val workerId: String? = null,
    val workerRequestId: String? = null,
    val accepted: Boolean = false,
) {
    init {
        require((workerId == null) == (workerRequestId == null)) { "Incomplete AI admission owner" }
    }
}

@Serializable
enum class AiAdmissionOutcome { SUCCEEDED, FAILED, CANCELLED, OWNER_STOPPED }

@Serializable
data class AiAdmissionSnapshot(
    val waiting: List<AiAdmissionOperation> = emptyList(),
    val active: List<AiAdmissionLease> = emptyList(),
    val tpmGroups: List<AiTpmState> = emptyList(),
)
