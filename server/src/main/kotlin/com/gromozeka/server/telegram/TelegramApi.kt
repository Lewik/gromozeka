package com.gromozeka.server.telegram

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface TelegramApi {
    suspend fun call(method: String, parameters: JsonObject = JsonObject(emptyMap())): JsonElement
}

class TelegramApiFailure(val code: Int, val retryAfterSeconds: Long? = null) :
    RuntimeException("Telegram API rejected request (code=$code)")

class TelegramDeliveryUncertain : RuntimeException("Telegram request outcome is unknown")

class TelegramHttpApi(
    private val token: String,
    private val endpoint: URI = URI("https://api.telegram.org/"),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) : TelegramApi {
    override suspend fun call(method: String, parameters: JsonObject): JsonElement = runInterruptible(Dispatchers.IO) {
        require(method in setOf("getMe", "getWebhookInfo", "getUpdates", "sendMessage", "editMessageText", "answerCallbackQuery",
            "getFile", "sendChatAction", "getMyName", "getMyDescription", "getMyShortDescription", "setMyName",
            "setMyDescription", "setMyShortDescription", "deleteMessage", "getChat"))
        val request = HttpRequest.newBuilder(endpoint.resolve("./bot$token/$method"))
            .timeout(Duration.ofSeconds(40))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(parameters.toString()))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: IOException) {
            throw TelegramDeliveryUncertain()
        }
        val body = try {
            response.body().use { stream ->
                val bytes = stream.readNBytes(8 * 1024 * 1024 + 1)
                if (bytes.size > 8 * 1024 * 1024) throw TelegramDeliveryUncertain()
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            }
        } catch (_: Exception) {
            throw TelegramDeliveryUncertain()
        }
        if (body["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val code = body["error_code"]?.jsonPrimitive?.intOrNull ?: response.statusCode()
            if (method == "editMessageText" && code == 400 &&
                body.string("description")?.contains("message is not modified", ignoreCase = true) == true) {
                return@runInterruptible buildJsonObject { put("message_id", parameters.getValue("message_id")) }
            }
            if (code >= 500) throw TelegramDeliveryUncertain()
            throw TelegramApiFailure(code, body["parameters"]?.jsonObject?.get("retry_after")?.jsonPrimitive?.longOrNull)
        }
        body["result"] ?: throw TelegramDeliveryUncertain()
    }

    suspend fun download(fileId: String, maximumBytes: Int): ByteArray {
        val file = call("getFile", buildJsonObject { put("file_id", fileId) }).jsonObject
        val path = file.string("file_path") ?: throw com.gromozeka.domain.service.ArtifactContentUnavailableException("Telegram file is unavailable")
        if ((file.long("file_size") ?: 0) > maximumBytes) throw com.gromozeka.domain.service.ArtifactContentUnavailableException("Telegram file exceeds the download limit")
        require(path.matches(Regex("[A-Za-z0-9_./-]+")) && path.split('/').none { it == ".." } && !path.startsWith('/'))
        return runInterruptible(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder(endpoint.resolve("./file/bot$token/$path"))
                    .timeout(Duration.ofSeconds(40)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { input ->
                    if (response.statusCode() != 200) throw com.gromozeka.domain.service.ArtifactContentUnavailableException("Telegram file download failed")
                    input.readNBytes(maximumBytes + 1).also {
                        if (it.size > maximumBytes) throw com.gromozeka.domain.service.ArtifactContentUnavailableException("Telegram file exceeds the download limit")
                    }
                }
            } catch (_: IOException) {
                throw com.gromozeka.domain.service.ArtifactContentUnavailableException("Telegram file download is unavailable")
            }
        }
    }

    suspend fun setProfilePhoto(jpeg: ByteArray): Unit = runInterruptible(Dispatchers.IO) {
        val boundary = "grz-${java.util.UUID.randomUUID()}"
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"photo\"\r\n\r\n" +
            "{\"type\":\"static\",\"photo\":\"attach://avatar\"}\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"avatar\"; filename=\"avatar.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
        val request = HttpRequest.newBuilder(endpoint.resolve("./bot$token/setMyProfilePhoto"))
            .timeout(Duration.ofSeconds(40)).header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(prefix.encodeToByteArray() + jpeg + "\r\n--$boundary--\r\n".encodeToByteArray()))
            .build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) } catch (_: IOException) { throw TelegramDeliveryUncertain() }
        val result = response.body().use { input ->
            val bytes = input.readNBytes(65537)
            if (bytes.size > 65536) throw TelegramDeliveryUncertain()
            try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject } catch (_: Exception) { throw TelegramDeliveryUncertain() }
        }
        if (!result.boolean("ok")) throw TelegramApiFailure((result.long("error_code") ?: response.statusCode().toLong()).toInt())
    }
}
