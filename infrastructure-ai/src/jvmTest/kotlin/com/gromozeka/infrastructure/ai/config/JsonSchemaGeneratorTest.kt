package com.gromozeka.infrastructure.ai.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.tool.filesystem.GetCommandTaskRequest
import com.gromozeka.domain.tool.filesystem.MAX_COMMAND_INITIAL_YIELD_MILLIS
import com.gromozeka.domain.tool.filesystem.MAX_COMMAND_TASK_WAIT_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaGeneratorTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val generator = JsonSchemaGenerator(objectMapper)

    @Test
    fun `command secret environment is an optional string map`() {
        val schema = objectMapper.readTree(generator.schemaFor(ExecuteCommandRequest::class))
        val environment = schema.path("properties").path("secret_env")
        assertEquals("object", environment.path("type").textValue())
        assertEquals("string", environment.path("additionalProperties").path("type").textValue())
        assertTrue(environment.path("description").textValue().contains("arguments"))
        assertTrue(schema.path("required").none { it.textValue() == "secret_env" })
        val request = objectMapper.readValue(
            """{"command":"gh api user","secret_env":{"GH_TOKEN":"secret://github-pat","OTHER_TOKEN":"secret://other"}}""",
            ExecuteCommandRequest::class.java,
        )
        assertEquals(mapOf("GH_TOKEN" to "secret://github-pat", "OTHER_TOKEN" to "secret://other"), request.secret_env)
        assertTrue(objectMapper.readValue("""{"command":"echo ok"}""", ExecuteCommandRequest::class.java).secret_env.isEmpty())
    }

    private data class Maps(val arbitrary: Map<String, Any?>, val nullableStrings: Map<String, String?>)

    @Test
    fun `map value schemas preserve arbitrary and nullable values`() {
        val properties = objectMapper.readTree(generator.schemaFor(Maps::class)).path("properties")
        assertEquals(0, properties.path("arbitrary").path("additionalProperties").size())
        val alternatives = properties.path("nullableStrings").path("additionalProperties").path("anyOf")
        assertEquals(setOf("string", "null"), alternatives.map { it.path("type").textValue() }.toSet())
    }

    @Test
    fun `command timing constraints are exposed in tool schemas`() {
        val executeProperties = objectMapper.readTree(generator.schemaFor(ExecuteCommandRequest::class))
            .path("properties")
        val getProperties = objectMapper.readTree(generator.schemaFor(GetCommandTaskRequest::class))
            .path("properties")

        assertEquals(0, executeProperties.path("yield_time_ms").path("minimum").longValue())
        assertEquals(
            MAX_COMMAND_INITIAL_YIELD_MILLIS,
            executeProperties.path("yield_time_ms").path("maximum").longValue(),
        )
        assertEquals(1, executeProperties.path("timeout_seconds").path("minimum").longValue())
        assertEquals("boolean", executeProperties.path("survive_worker_restart").path("type").textValue())
        assertTrue(executeProperties.path("survive_worker_restart").path("description").textValue().isNotBlank())
        assertEquals(0, getProperties.path("after_byte").path("minimum").longValue())
        assertEquals(
            MAX_COMMAND_TASK_WAIT_MILLIS,
            getProperties.path("wait_ms").path("maximum").longValue(),
        )
        assertTrue(getProperties.path("wait_ms").path("description").textValue().isNotBlank())
    }
}
