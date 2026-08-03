package com.gromozeka.infrastructure.ai.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

@Configuration
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class ToolsRegistrationConfig(
    private val adapter: TypedToolCallbackAdapter,
) {
    private val logger = LoggerFactory.getLogger(ToolsRegistrationConfig::class.java)

    @Bean
    fun toolCallbacksRegistrar(
        tools: List<Tool<*, *>>,
    ): ToolCallbacksRegistrar {
        logger.info("=== ToolCallbacksRegistrar: Starting tool registration ===")
        logger.info("Found ${tools.size} Tool beans in Spring context")
        tools.forEach { tool ->
            logger.info("  - Tool bean: ${tool::class.qualifiedName}, name='${tool.name}', description='${tool.description.take(50)}...'")
        }

        val callbacks = tools
            .filter { it.metadata.executionScope != AiToolExecutionScope.SERVER }
            .map(adapter::adapt)
        logger.info("Prepared ${callbacks.size} local AI tool callbacks: ${callbacks.map { it.definition.name }}")
        return ToolCallbacksRegistrar(callbacks)
    }

}

@Component
class TypedToolCallbackAdapter {
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Suppress("UNCHECKED_CAST")
    fun adapt(tool: Tool<*, *>): AiToolCallback =
        adaptTyped(tool as Tool<Any, Any?>)

    private fun <TRequest, TResponse> adaptTyped(tool: Tool<TRequest, TResponse>): AiToolCallback {
        val schema = JsonSchemaGenerator(objectMapper).schemaFor((tool.requestType as Class<*>).kotlin)

        return object : AiToolCallback {
            override val definition: AiToolDefinition = AiToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = schema,
            )
            override val metadata = tool.metadata
            override val available: Boolean
                get() = tool.available

            override fun call(toolInput: String, context: ToolExecutionContext?): String {
                val request = objectMapper.readValue(toolInput, tool.requestType)
                val response = tool.execute(request, context)
                return when (response) {
                    is String -> response
                    is AiToolResult.Text -> response.content
                    is AiToolResult.Binary -> objectMapper.writeValueAsString(
                        mapOf(
                            "file_name" to response.fileName,
                            "media_type" to response.mediaType,
                            "size_bytes" to response.content.size,
                        )
                    )
                    else -> objectMapper.writeValueAsString(response)
                }
            }

            override fun callResult(toolInput: String, context: ToolExecutionContext?): List<AiToolResult> {
                val request = objectMapper.readValue(toolInput, tool.requestType)
                val response = tool.execute(request, context)
                return when (response) {
                    is AiToolResult -> listOf(response)
                    is String -> listOf(AiToolResult.Text(response))
                    else -> listOf(AiToolResult.Text(objectMapper.writeValueAsString(response)))
                }
            }
        }
    }
}

class ToolCallbacksRegistrar(
    override val callbacks: List<AiToolCallback>,
) : AiToolCallbackContributor

internal class JsonSchemaGenerator(
    private val objectMapper: ObjectMapper,
) {
    fun schemaFor(type: KClass<*>): String =
        objectMapper.writeValueAsString(objectSchema(type, mutableSetOf()))

    private fun objectSchema(type: KClass<*>, seen: MutableSet<KClass<*>>): Map<String, Any> {
        if (!seen.add(type)) {
            return mapOf("type" to "object")
        }

        val constructor = type.primaryConstructor
        val optionalParameters = constructor
            ?.parameters
            ?.filter { it.isOptional }
            ?.mapNotNull { it.name }
            ?.toSet()
            ?: emptySet()

        val properties = type.memberProperties.associate { property ->
            val schema = schemaForType(property.returnType, seen).toMutableMap()
            property.findAnnotation<ToolParameter>()?.let { parameter ->
                if (parameter.description.isNotBlank()) {
                    schema["description"] = parameter.description
                }
                if (parameter.minimum != Long.MIN_VALUE) {
                    schema["minimum"] = parameter.minimum
                }
                if (parameter.maximum != Long.MAX_VALUE) {
                    schema["maximum"] = parameter.maximum
                }
            }
            property.name to schema
        }
        val required = type.memberProperties
            .filter { property -> property.name !in optionalParameters && !property.returnType.isMarkedNullable }
            .map { it.name }

        seen.remove(type)

        return buildMap {
            put("type", "object")
            put("properties", properties)
            put("additionalProperties", false)
            if (required.isNotEmpty()) {
                put("required", required)
            }
        }
    }

    private fun schemaForType(type: KType, seen: MutableSet<KClass<*>>): Map<String, Any> {
        val classifier = type.jvmErasure
        return when {
            classifier == String::class -> mapOf<String, Any>("type" to "string")
            classifier == Boolean::class -> mapOf<String, Any>("type" to "boolean")
            classifier in integerTypes -> mapOf<String, Any>("type" to "integer")
            classifier in numberTypes -> mapOf<String, Any>("type" to "number")
            classifier.java.isEnum -> mapOf<String, Any>(
                "type" to "string",
                "enum" to classifier.java.enumConstants.map { (it as Enum<*>).name },
            )
            classifier == List::class || classifier == Set::class -> {
                val itemSchema: Map<String, Any> = type.arguments.firstOrNull()?.type?.let { schemaForType(it, seen) }
                    ?: mapOf("type" to "object")
                mapOf<String, Any>("type" to "array", "items" to itemSchema)
            }
            classifier == Map::class -> mapOf<String, Any>("type" to "object")
            classifier.isData -> objectSchema(classifier, seen)
            else -> mapOf<String, Any>("type" to "object")
        }
    }

    private companion object {
        val integerTypes = setOf(Byte::class, Short::class, Int::class, Long::class)
        val numberTypes = setOf(Float::class, Double::class)
    }
}
