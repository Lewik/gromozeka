package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition

internal fun AgentDefinition.validatedIdentity(
    expectedId: AgentDefinition.Id,
    expectedType: AgentDefinition.Type,
    sourceName: String,
): AgentDefinition {
    require(id == expectedId) {
        "Agent file $sourceName has id=${id.value}, expected ${expectedId.value}"
    }
    require(type == expectedType) {
        "Agent file $sourceName has type=$type, expected $expectedType"
    }
    return this
}
