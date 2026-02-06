package com.gromozeka.infrastructure.ai.oauth

import klog.KLoggers
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.charset.StandardCharsets

class AnthropicOAuthWebClientFilter(
    private val toolPrefix: String,
    private val userAgent: String
) : ExchangeFilterFunction {

    private val log = KLoggers.logger(this)
    private val bufferFactory = DefaultDataBufferFactory()

    override fun filter(request: ClientRequest, next: ExchangeFunction): Mono<ClientResponse> {
        val modifiedRequest = ClientRequest.from(request)
            .headers { headers ->
                headers["user-agent"] = userAgent
            }
            .url(addBetaParameter(request.url()))
            .build()

        return next.exchange(modifiedRequest)
            .flatMap { response ->
                Mono.just(
                    response.mutate()
                        .body { flux -> transformResponseBody(flux) }
                        .build()
                )
            }
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

    private fun transformResponseBody(flux: Flux<DataBuffer>): Flux<DataBuffer> {
        return flux.map { dataBuffer ->
            try {
                val bytes = ByteArray(dataBuffer.readableByteCount())
                dataBuffer.read(bytes)
                DataBufferUtils.release(dataBuffer)

                val bodyStr = String(bytes, StandardCharsets.UTF_8)
                val modifiedStr = bodyStr.replace(
                    Regex("\"name\"\\s*:\\s*\"$toolPrefix([^\"]+)\""),
                    "\"name\": \"\$1\""
                )

                bufferFactory.wrap(modifiedStr.toByteArray(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                log.warn(e) { "Failed to transform response chunk" }
                dataBuffer
            }
        }
    }
}
