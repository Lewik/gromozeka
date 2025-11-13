package com.gromozeka.bot.services.memory.graph.models

import kotlinx.datetime.Instant

data class MemoryLink(
    val uuid: String,
    val sourceNodeUuid: String,
    val targetNodeUuid: String,
    val relationType: String,
    val description: String,
    val embedding: List<Float>?,
    val validAt: Instant?,
    val invalidAt: Instant?,
    val createdAt: Instant,
    val expiredAt: Instant?,
    val sources: List<String>,
    val groupId: String,
    val attributes: Map<String, Any> = emptyMap()
)
