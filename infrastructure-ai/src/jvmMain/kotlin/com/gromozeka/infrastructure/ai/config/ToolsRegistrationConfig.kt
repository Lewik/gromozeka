package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.function.BiFunction

/**
 * Автоматическая регистрация всех Tool бинов как framework-agnostic AiToolCallback beans.
 *
 * Spring AI используется здесь только как внутренний helper для JSON schema
 * generation и typed argument decoding. Наружу отдается только AiToolCallback.
 */
@Configuration
class ToolsRegistrationConfig {

    private val logger = LoggerFactory.getLogger(ToolsRegistrationConfig::class.java)

    /**
     * Регистрирует каждый Tool как отдельный AiToolCallback bean.
     *
     * ВАЖНО: Возвращаем не List<AiToolCallback>, а регистрируем каждый тул
     * как отдельный singleton через BeanFactory.registerSingleton().
     * Это позволяет собирать инструменты из Spring context без утечки
     * Spring AI типов наружу.
     *
     * @param tools Все бины, имплементирующие Tool<*, *> (автоматически инжектятся Spring)
     * @param beanFactory Spring bean factory для динамической регистрации бинов
     * @return Маркер-объект подтверждающий регистрацию (для логирования)
     */
    @Bean
    fun toolCallbacksRegistrar(
        tools: List<Tool<*, *>>,
        beanFactory: ConfigurableListableBeanFactory
    ): ToolCallbacksRegistrar {
        logger.info("=== ToolCallbacksRegistrar: Starting tool registration ===")
        logger.info("Found ${tools.size} Tool beans in Spring context")
        tools.forEach { tool ->
            logger.info("  - Tool bean: ${tool::class.qualifiedName}, name='${tool.name}', description='${tool.description.take(50)}...'")
        }

        logger.info("Registering ${tools.size} tools as individual AiToolCallback beans: ${tools.map { it.name }}")

        tools.forEach { tool ->
            val callback = adaptToolToCallback(tool)
            val beanName = "${tool.name}AiToolCallback"

            // Регистрируем каждый AiToolCallback как отдельный singleton bean
            beanFactory.registerSingleton(beanName, callback)
            logger.debug("Registered AiToolCallback bean: $beanName")
        }

        return ToolCallbacksRegistrar(tools.size)
    }

    /**
     * Адаптер Tool -> AiToolCallback.
     *
     * Внутри использует Spring's FunctionToolCallback только как helper
     * для schema generation и JSON -> typed request decoding.
     */
    private fun <TRequest, TResponse> adaptToolToCallback(
        tool: Tool<TRequest, TResponse>
    ): AiToolCallback {
        val function = BiFunction<TRequest, org.springframework.ai.chat.model.ToolContext?, TResponse> { request, context ->
            val domainContext = context?.let { ToolExecutionContext(it.context) }
            tool.execute(request, domainContext)
        }

        val springCallback = FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<TRequest>() {
                override fun getType() = tool.requestType
            })
            .build()

        return object : AiToolCallback {
            override val definition: AiToolDefinition = AiToolDefinition(
                name = springCallback.toolDefinition.name(),
                description = springCallback.toolDefinition.description(),
                inputSchema = springCallback.toolDefinition.inputSchema()
            )

            override val metadata: AiToolMetadata = AiToolMetadata(
                returnDirect = springCallback.toolMetadata?.returnDirect() == true
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String {
                val springContext = context?.let { org.springframework.ai.chat.model.ToolContext(it.asMap()) }
                return springCallback.call(toolInput, springContext)
            }
        }
    }
}

/**
 * Маркер-класс для подтверждения регистрации тулов.
 * Содержит количество зарегистрированных тулов для логирования/мониторинга.
 */
class ToolCallbacksRegistrar(val toolCount: Int)
