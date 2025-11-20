package com.gromozeka.shared.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json {
    prettyPrint = true
}

fun jsonPrettyPrint(json: JsonElement) = prettyJson.encodeToString(JsonElement.Companion.serializer(), json)

fun jsonPrettyPrint(jsonString: String): String {
    return try {
        val jsonElement = Json.parseToJsonElement(jsonString)
        prettyJson.encodeToString(JsonElement.serializer(), jsonElement)
    } catch (e: Exception) {
        jsonString
    }
}