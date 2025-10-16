package com.gromozeka.bot.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.vertexai.VertexAI
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions
import org.springframework.ai.vertexai.gemini.schema.VertexToolCallingManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.support.RetryTemplate
import java.io.FileInputStream


//@Configuration
class GeminiConfigDisabled {

    @Bean
    fun vertexAI(
        @Value("\${spring.ai.vertex.ai.gemini.project-id}") projectId: String,
        @Value("\${spring.ai.vertex.ai.gemini.location}") location: String,
        @Value("\${spring.ai.vertex.ai.gemini.credentials-uri}") credentialsUri: String,
    ): VertexAI {
        val credentials = GoogleCredentials.fromStream(
            FileInputStream(credentialsUri.removePrefix("file:"))
        ).createScoped("https://www.googleapis.com/auth/cloud-platform")
        return VertexAI.Builder()
            .setProjectId(projectId)
            .setLocation(location)
            .setCredentials(credentials)
            .build()
    }

    @Bean
    fun toolCallingManager(): ToolCallingManager {
        return ToolCallingManager.builder().build()
    }

    @Bean
    fun vertexAiGeminiChatModel(
        vertexAI: VertexAI,
        toolCallingManager: ToolCallingManager,
        @Value("\${spring.ai.vertex.ai.gemini.chat.options.model}") model: String,
        @Value("\${spring.ai.vertex.ai.gemini.chat.options.temperature}") temperature: Double,
    ): VertexAiGeminiChatModel {
        val options = VertexAiGeminiChatOptions
            .builder()
            .model(model)
            .temperature(temperature)
            .build()
        val retryTemplate = RetryTemplate.builder().maxAttempts(3).build()
        val observationRegistry = ObservationRegistry.create()

        return VertexAiGeminiChatModel(
            vertexAI,
            options,
            VertexToolCallingManager(toolCallingManager),
            retryTemplate,
            observationRegistry
        )
    }

    @Bean
    fun chatClient(chatModel: VertexAiGeminiChatModel): ChatClient {
        return ChatClient.builder(chatModel).build()
    }
}
