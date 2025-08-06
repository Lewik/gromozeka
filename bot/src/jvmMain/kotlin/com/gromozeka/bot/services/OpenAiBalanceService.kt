package com.gromozeka.bot.services

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class OpenAiBalanceService(
    @Value("\${spring.ai.openai.api-key}") private val apiKey: String,
    private val httpClient: HttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class UsageResponse(
        @SerialName("total_usage") val totalUsage: Double = 0.0,
        @SerialName("object") val objectType: String = "",
    )

    suspend fun checkBalance(): String = withContext(Dispatchers.IO) {
        try {
            val today = Clock.System.todayIn(TimeZone.UTC)

            val response = httpClient.get("https://api.openai.com/v1/usage") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                parameter("date", today.toString())
            }

            val responseBody = response.bodyAsText()

            if (response.status.isSuccess()) {
                val usage = json.decodeFromString<UsageResponse>(responseBody)
                "💰 OpenAI Usage ($today): $${String.format("%.4f", usage.totalUsage / 100)}"
            } else {
                "Error checking usage: ${response.status} - $responseBody"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}