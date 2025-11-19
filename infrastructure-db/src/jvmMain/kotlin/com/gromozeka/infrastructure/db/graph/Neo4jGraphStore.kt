package com.gromozeka.infrastructure.db.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.stereotype.Service

@Service
class Neo4jGraphStore(
    private val driver: Driver
) {

    companion object {
        private val VALID_IDENTIFIER = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
        
        private fun validateIdentifier(value: String, name: String) {
            require(value.isNotBlank()) { "$name cannot be blank" }
            require(VALID_IDENTIFIER.matches(value)) { 
                "$name contains invalid characters: $value. Only alphanumeric and underscore allowed." 
            }
        }
    }

    suspend fun executeQuery(
        cypher: String,
        params: Map<String, Any> = emptyMap()
    ): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        driver.session().use { session ->
            val result = session.run(cypher, Values.value(params))
            result.list { record ->
                record.asMap()
            }
        }
    }

    suspend fun findNodes(
        label: String,
        properties: Map<String, Any> = emptyMap()
    ): List<Map<String, Any>> {
        validateIdentifier(label, "Label")
        properties.keys.forEach { validateIdentifier(it, "Property key") }
        
        val whereClause = if (properties.isEmpty()) {
            ""
        } else {
            "WHERE " + properties.entries.joinToString(" AND ") { 
                "n.${it.key} = ${'$'}${it.key}" 
            }
        }
        val cypher = "MATCH (n:$label) $whereClause RETURN n"
        
        return executeQuery(cypher, properties)
    }

    suspend fun createRelationship(
        fromLabel: String,
        fromId: String,
        relationshipType: String,
        toLabel: String,
        toId: String,
        properties: Map<String, Any> = emptyMap()
    ) {
        validateIdentifier(fromLabel, "From label")
        validateIdentifier(toLabel, "To label")
        validateIdentifier(relationshipType, "Relationship type")
        
        val cypher = """
            MATCH (from:$fromLabel {id: ${'$'}fromId})
            MATCH (to:$toLabel {id: ${'$'}toId})
            CREATE (from)-[r:$relationshipType]->(to)
            SET r = ${'$'}props
        """.trimIndent()

        executeQuery(
            cypher,
            mapOf("fromId" to fromId, "toId" to toId, "props" to properties)
        )
    }

    suspend fun createNode(
        label: String,
        properties: Map<String, Any>
    ): Map<String, Any> {
        validateIdentifier(label, "Label")
        
        val cypher = """
            CREATE (n:$label)
            SET n = ${'$'}props
            RETURN n
        """.trimIndent()

        val results = executeQuery(cypher, mapOf("props" to properties))
        return results.firstOrNull() ?: emptyMap()
    }

    suspend fun deleteNode(
        label: String,
        id: String,
        cascade: Boolean = false
    ) {
        validateIdentifier(label, "Label")
        
        val cypher = if (cascade) {
            "MATCH (n:$label {id: ${'$'}id}) DETACH DELETE n"
        } else {
            "MATCH (n:$label {id: ${'$'}id}) DELETE n"
        }

        executeQuery(cypher, mapOf("id" to id))
    }
}
