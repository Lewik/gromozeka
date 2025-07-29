package com.gromozeka.bot.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


class PluginService(private val root: String) {

    suspend fun openDiff(list: List<DiffRequest>) {
        val client = HttpClient(CIO)
        val absolutePathsList = list.map { it.copy(path = root.trimEnd('/') + '/' + it.path.trimStart('/')) }
        val body = Json.encodeToString(absolutePathsList)
        println("Sending to plugin: $body")
        client.post("http://localhost:5678/applyChanges") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun openFile(path: String) {
        val client = HttpClient(CIO)
        val absolutePath = root.trimEnd('/') + '/' + path.trimStart('/')
        println("Sending to plugin: $absolutePath")
        client.post("http://localhost:5678/openFile") {
            contentType(ContentType.Application.Json)
            setBody(OpenFileRequest(absolutePath))
        }
    }

    suspend fun getIdeContext(): IdeContextResponse {
        val client = HttpClient(CIO)
        val raw = client.get("http://localhost:5678/getIdeContext").bodyAsText()
//        println("Got raw IDE context: $raw")
        return Json.decodeFromString(raw)
    }
}


@Serializable
data class DiffRequest(val path: String, val content: String)

@Serializable
data class ReplaceRequest(val text: String)

@Serializable
data class InsertRequest(val text: String)

@Serializable
data class OpenFileRequest(val path: String)

@Serializable
data class IdeContextResponse(
    val selection: String,
    val fullText: String,
    val caretLine: Int,
    val caretColumn: Int,
    val filePath: String,
    val language: String,
    val openedFiles: List<String>,
)
