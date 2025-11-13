package com.gromozeka.bot.services.memory.graph.models

import kotlinx.serialization.Serializable

@Serializable
data class EntityType(
    val id: Int,
    val name: String,
    val description: String
)

@Serializable
data class EntityTypesConfig(
    val entity_types: List<EntityType>
)
