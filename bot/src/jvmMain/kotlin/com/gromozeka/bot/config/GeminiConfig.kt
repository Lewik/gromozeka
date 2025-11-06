package com.gromozeka.bot.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.vertexai.VertexAI
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream


@Configuration
class GeminiConfig {

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

}
