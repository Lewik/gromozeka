package com.gromozeka.infrastructure.ai.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.Client
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream


@Configuration
class GeminiConfig {

    // DISABLED: Gemini requires google-credentials.json which is not available in production
    // Uncomment when credentials are available for development
    /*
    @Bean
    fun geminiClient(
        @Value("\${spring.ai.google-genai.project-id}") projectId: String,
        @Value("\${spring.ai.google-genai.location}") location: String,
        @Value("\${spring.ai.google-genai.credentials-uri}") credentialsUri: String,
    ): Client {
        val credentials = GoogleCredentials.fromStream(
            FileInputStream(credentialsUri.removePrefix("file:"))
        ).createScoped("https://www.googleapis.com/auth/cloud-platform")
        return Client.builder()
            .project(projectId)
            .location(location)
            .vertexAI(true)
            .credentials(credentials)
            .build()
    }
    */

    @Bean
    fun toolCallingManager(): ToolCallingManager {
        return ToolCallingManager.builder().build()
    }

}
