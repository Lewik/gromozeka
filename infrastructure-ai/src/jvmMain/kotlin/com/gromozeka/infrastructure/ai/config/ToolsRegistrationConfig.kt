package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.tool.Tool
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.function.BiFunction

/**
 * Автоматическая регистрация всех Tool бинов как Spring AI ToolCallbacks.
 * 
 * Spring находит все бины, имплементирующие Tool<*, *>, и конвертирует их
 * в отдельные ToolCallback бины через FunctionToolCallback.builder().
 * 
 * Каждый Tool регистрируется как отдельный singleton bean типа ToolCallback,
 * чтобы Spring автоматически собирал их для ChatModel.
 */
@Configuration
class ToolsRegistrationConfig {
    
    private val logger = LoggerFactory.getLogger(ToolsRegistrationConfig::class.java)
    
    /**
     * Регистрирует каждый Tool как отдельный ToolCallback bean.
     * 
     * ВАЖНО: Возвращаем не List<ToolCallback>, а регистрируем каждый тул 
     * как отдельный singleton через BeanFactory.registerSingleton().
     * Это позволяет Spring автоматически собрать все ToolCallback бины
     * для инъекции в ChatModel.
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
        
        logger.info("Registering ${tools.size} tools as individual ToolCallback beans: ${tools.map { it.name }}")
        
        tools.forEach { tool ->
            val callback = adaptToolToCallback(tool)
            val beanName = "${tool.name}ToolCallback"
            
            // Регистрируем каждый ToolCallback как отдельный singleton bean
            beanFactory.registerSingleton(beanName, callback)
            logger.debug("Registered ToolCallback bean: $beanName")
        }
        
        return ToolCallbacksRegistrar(tools.size)
    }
    
    /**
     * Адаптер Tool -> ToolCallback.
     * Конвертирует типизированный Tool в Spring AI BiFunction формат.
     */
    private fun <TRequest, TResponse> adaptToolToCallback(
        tool: Tool<TRequest, TResponse>
    ): ToolCallback {
        // BiFunction принимает (request, context) и возвращает response
        val function = BiFunction<TRequest, ToolContext?, TResponse> { request, context ->
            tool.execute(request, context)
        }
        
        // FunctionToolCallback.builder автоматически генерирует JSON Schema
        // из requestType через ParameterizedTypeReference
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<TRequest>() {
                override fun getType() = tool.requestType
            })
            .build()
    }
}

/**
 * Маркер-класс для подтверждения регистрации тулов.
 * Содержит количество зарегистрированных тулов для логирования/мониторинга.
 */
class ToolCallbacksRegistrar(val toolCount: Int)
