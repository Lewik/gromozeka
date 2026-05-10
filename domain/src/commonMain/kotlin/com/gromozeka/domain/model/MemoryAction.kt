package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryAction {
    REMEMBER_THREAD,
    CONSOLIDATE,
    REPAIR,
    MAINTAIN_ENTITIES,
    APPLY_RETENTION
}
