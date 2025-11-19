package com.gromozeka.bot.domain.model.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Entity type definition from entity-types.json configuration.
 *
 * Defines predefined categories for knowledge graph entities (Person, Technology, Concept, etc.).
 * Used by LLM during entity extraction to classify entities into standardized types.
 *
 * Configuration loaded from: `infrastructure-ai/src/jvmMain/resources/memory/entity-types.json`
 *
 * @property id numeric type identifier used by LLM for classification
 * @property name human-readable type name (e.g., "Person", "Technology", "Organization")
 * @property description explanation of what entities belong to this type
 */
@Serializable
data class EntityType(
    val id: Int,
    val name: String,
    val description: String
)

/**
 * Wrapper for deserializing entity-types.json configuration file.
 *
 * Uses kotlinx.serialization for JSON deserialization.
 *
 * JSON structure:
 * ```json
 * {
 *   "entity_types": [
 *     {"id": 1, "name": "Person", "description": "..."},
 *     {"id": 2, "name": "Technology", "description": "..."}
 *   ]
 * }
 * ```
 *
 * @property entityTypes list of entity type definitions
 */
@Serializable
data class EntityTypesConfig(
    @SerialName("entity_types")
    val entityTypes: List<EntityType>
)
