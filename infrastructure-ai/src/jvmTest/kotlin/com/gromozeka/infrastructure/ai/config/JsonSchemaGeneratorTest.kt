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
        assertEquals(0, getProperties.path("after_byte").path("minimum").longValue())
        assertEquals(
            MAX_COMMAND_TASK_WAIT_MILLIS,
            getProperties.path("wait_ms").path("maximum").longValue(),
        )
        assertTrue(getProperties.path("wait_ms").path("description").textValue().isNotBlank())
    }
}
