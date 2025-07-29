package com.gromozeka.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage

class TheAssistant(
    private val chatClient: ChatClient,
    private val chatMemory: ChatMemory
) {
    // Храним текущую информацию о запросе инструментов
    private var pendingToolCalls: Boolean = false
    private var pendingToolRequest: String? = null

    suspend fun init() = withContext(Dispatchers.IO) {
        // Добавляем системное сообщение с информацией о том, что модель может использовать инструменты
        val systemPrompt = "Ты полезный помощник. У тебя есть доступ к внешним инструментам, " +
                "таким как поиск в браузере Brave. " +
                "Когда тебе нужно воспользоваться инструментом, опиши какой инструмент ты хочешь использовать и с какими параметрами."
        
        chatMemory.add("conversationId", SystemMessage(systemPrompt))
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
//        // Сбрасываем состояние ожидающих инструментов
//        pendingToolCalls = false
//        pendingToolRequest = null
//
//        chatMemory.add("conversationId", UserMessage(message))
//
//        val messages = chatMemory.get("conversationId")
//        val response = chatClient.call(messages)
//
//        // Проверяем, содержит ли ответ запрос на использование инструмента
//        val content = response.content.toString()
//
//        if (containsToolRequest(content)) {
//            println("Обнаружен запрос на использование инструмента")
//
//            // Сохраняем запрос инструмента для последующего выполнения
//            pendingToolCalls = true
//            pendingToolRequest = content
//
//            // Добавляем в историю только часть без запроса инструмента
//            val cleanContent = content.split("Я хочу использовать инструмент").first().trim()
//
//            if (cleanContent.isNotEmpty()) {
//                chatMemory.add("conversationId", AssistantMessage(cleanContent))
//            }
//
//            // Добавляем сообщение о том, что есть ожидающие инструменты
//            chatMemory.add(
//                "conversationId",
//                UserMessage("[Есть запрос на использование инструмента. Ожидание подтверждения пользователя.]")
//            )
//        } else {
//            // Если нет запроса на использование инструмента, добавляем ответ в историю
//            chatMemory.add("conversationId", response)
//        }
    }

    // Метод для проверки наличия ожидающих инструментов
    fun hasPendingToolCalls(): Boolean {
        return pendingToolCalls
    }

    // Метод для получения информации об ожидающих инструментах
    fun getPendingToolCallInfo(): List<Map<String, String>> {
        if (!pendingToolCalls || pendingToolRequest == null) {
            return emptyList()
        }
        
        // Анализируем текст запроса
        val requestData = mutableMapOf<String, String>()
        requestData["request"] = pendingToolRequest ?: ""
        
        return listOf(requestData)
    }

    // Метод для запуска выполнения ожидающих инструментов
    suspend fun executeToolCalls() = withContext(Dispatchers.IO) {
//        if (!pendingToolCalls || pendingToolRequest == null) {
//            println("Нет ожидающих инструментов для выполнения")
//            return@withContext
//        }
//
//        try {
//            // Добавляем запрос и разрешение использовать инструмент
//            chatMemory.add("conversationId", AssistantMessage(pendingToolRequest!!))
//            chatMemory.add("conversationId", UserMessage("Да, используй инструмент"))
//
//            // Получаем новый ответ с использованием инструмента
//            val messages = chatMemory.get("conversationId")
//            val response = chatClient.call(messages)
//
//            // Добавляем ответ в историю
//            chatMemory.add("conversationId", response)
//
//            // Сбрасываем состояние ожидающих инструментов
//            pendingToolCalls = false
//            pendingToolRequest = null
//        } catch (e: Exception) {
//            println("Ошибка при выполнении инструмента: ${e.message}")
//            chatMemory.add("conversationId", UserMessage("Ошибка при выполнении инструмента: ${e.message}"))
//
//            // Сбрасываем состояние ожидающих инструментов
//            pendingToolCalls = false
//            pendingToolRequest = null
//        }
    }
    
    // Метод для определения, содержит ли ответ запрос на использование инструмента
    private fun containsToolRequest(content: String): Boolean {
        val patterns = listOf(
            "Я хочу использовать инструмент",
            "Я должен использовать инструмент",
            "Мне нужно использовать инструмент",
            "Воспользуюсь инструментом",
            "Let me use the tool",
            "I need to use the tool"
        )
        
        return patterns.any { content.contains(it, ignoreCase = true) }
    }
}