package com.gromozeka.infrastructure.ai.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

@Configuration
class ToolsRegistrationConfig {
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val logger = LoggerFactory.getLogger(ToolsRegistrationConfig::class.java)
    private val disabledToolNames = setOf("unified_search")

    @Bean
    fun toolCallbacksRegistrar(
        tools: List<Tool<*, *>>,
        beanFactory: ConfigurableListableBeanFactory,
    ): ToolCallbacksRegistrar {
        logger.info("=== ToolCallbacksRegistrar: Starting tool registration ===")
        logger.info("Found ${tools.size} Tool beans in Spring context")
        tools.forEach { tool ->
            logger.info("  - Tool bean: ${tool::class.qualifiedName}, name='${tool.name}', description='${tool.description.take(50)}...'")
        }

        val enabledTools = tools.filterNot { it.name in disabledToolNames }
        val disabledTools = tools.filter { it.name in disabledToolNames }

        if (disabledTools.isNotEmpty()) {
            logger.info("Skipping disabled tools: ${disabledTools.map { it.name }}")
        }

        logger.info("Registering ${enabledTools.size} tools as individual AiToolCallback beans: ${enabledTools.map { it.name }}")

        enabledTools.forEach { tool ->
            val callback = adaptToolToCallback(tool)
            val beanName = "${tool.name}AiToolCallback"

            beanFactory.registerSingleton(beanName, callback)
            logger.debug("Registered AiToolCallback bean: $beanName")
        }

        return ToolCallbacksRegistrar(enabledTools.size)
    }

    private fun <TRequest, TResponse> adaptToolToCallback(
        tool: Tool<TRequest, TResponse>,
    ): AiToolCallback {
        val schema = JsonSchemaGenerator(objectMapper).schemaFor((tool.requestType as Class<*>).kotlin)

        return object : AiToolCallback {
            override val definition: AiToolDefinition = AiToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = schema,
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String {
                val request = objectMapper.readValue(toolInput, tool.requestType)
                val response = tool.execute(request, context)
                return when (response) {
                    is String -> response
                    else -> objectMapper.writeValueAsString(response)
                }
            }
        }
    }
}

class ToolCallbacksRegistrar(val toolCount: Int)

private class JsonSchemaGenerator(
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
            property.name to schemaForType(property.returnType, seen)
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
