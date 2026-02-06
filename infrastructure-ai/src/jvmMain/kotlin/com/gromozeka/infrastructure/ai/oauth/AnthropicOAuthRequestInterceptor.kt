package com.gromozeka.infrastructure.ai.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import klog.KLoggers
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.StreamUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI

class AnthropicOAuthRequestInterceptor(
    private val toolPrefix: String,
    private val userAgent: String
) : ClientHttpRequestInterceptor {

    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution
    ): ClientHttpResponse {
        addUserAgent(request)
        val modifiedUri = addBetaParameter(request.uri)
        val modifiedBody = transformRequestBody(body)

        val modifiedRequest = object : HttpRequest by request {
            override fun getURI(): URI = modifiedUri
        }

        val response = execution.execute(modifiedRequest, modifiedBody)

        return transformResponseBody(response)
    }

    private fun addUserAgent(request: HttpRequest) {
        request.headers["user-agent"] = userAgent
    }

    private fun addBetaParameter(uri: URI): URI {
        val query = uri.query ?: ""
        if (query.contains("beta=")) {
            return uri
        }

        val newQuery = if (query.isEmpty()) "beta=true" else "$query&beta=true"
        return URI(
            uri.scheme,
            uri.authority,
            uri.path,
            newQuery,
            uri.fragment
        )
    }

    private fun transformRequestBody(body: ByteArray): ByteArray {
        if (body.isEmpty()) return body

        return try {
            val json = objectMapper.readTree(body) as? ObjectNode ?: return body

            if (json.has("tools")) {
                val tools = json.get("tools") as? ArrayNode
                tools?.forEach { tool ->
                    if (tool is ObjectNode && tool.has("name")) {
                        val name = tool.get("name").asText()
                        if (!name.startsWith(toolPrefix)) {
                            tool.put("name", "$toolPrefix$name")
                        }
                    }
                }
            }

            if (json.has("messages")) {
                val messages = json.get("messages") as? ArrayNode
                messages?.forEach { message ->
                    if (message is ObjectNode && message.has("content")) {
                        val content = message.get("content")
                        if (content is ArrayNode) {
                            content.forEach { block ->
                                if (block is ObjectNode && block.has("type")) {
                                    val type = block.get("type").asText()
                                    if (type == "tool_use" && block.has("name")) {
                                        val name = block.get("name").asText()
                                        if (!name.startsWith(toolPrefix)) {
                                            block.put("name", "$toolPrefix$name")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            objectMapper.writeValueAsBytes(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to transform request body, using original" }
            body
        }
    }

    private fun transformResponseBody(response: ClientHttpResponse): ClientHttpResponse {
        return try {
            val originalBody = StreamUtils.copyToByteArray(response.body)
            val modifiedBody = stripMcpPrefixFromResponse(originalBody)

            object : ClientHttpResponse by response {
                override fun getBody(): InputStream = ByteArrayInputStream(modifiedBody)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to transform response body, using original" }
            response
        }
    }

    private fun stripMcpPrefixFromResponse(body: ByteArray): ByteArray {
        if (body.isEmpty()) return body

        return try {
            val bodyStr = String(body, Charsets.UTF_8)
            val modifiedStr = bodyStr.replace(Regex("\"name\"\\s*:\\s*\"$toolPrefix([^\"]+)\""), "\"name\": \"\$1\"")
            modifiedStr.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            log.warn(e) { "Failed to strip $toolPrefix prefix from response" }
            body
        }
    }
}
