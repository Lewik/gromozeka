package com.gromozeka.infrastructure.db.memory.graph.models

import com.fasterxml.jackson.annotation.JsonProperty

data class NodeDuplicate(
    val id: Int,
    @JsonProperty("duplicate_idx")
    val duplicateIdx: Int,
    val name: String,
    val duplicates: List<Int>
)

data class NodeResolutions(
    @JsonProperty("entity_resolutions")
    val entityResolutions: List<NodeDuplicate>
)

data class MissedEntities(
    @JsonProperty("missed_entities")
    val missedEntities: List<String>
)
