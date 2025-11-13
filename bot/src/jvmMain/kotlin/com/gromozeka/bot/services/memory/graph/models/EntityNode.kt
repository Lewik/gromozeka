package com.gromozeka.bot.services.memory.graph.models

import kotlinx.datetime.Instant

data class MemoryObject(
    val uuid: String,
    val name: String,
    val embedding: List<Float>?,
    val summary: String,
    val groupId: String,
    val labels: List<String>,
    val createdAt: Instant,
    val attributes: Map<String, Any> = emptyMap()
)
