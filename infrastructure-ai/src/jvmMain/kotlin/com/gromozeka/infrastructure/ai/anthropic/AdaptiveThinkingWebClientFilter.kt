package com.gromozeka.infrastructure.ai.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import klog.KLoggers
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.http.client.reactive.ClientHttpRequestDecorator
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * WebClient filter that injects adaptive thinking and effort into streaming Anthropic requests.
 *
 * Mirrors [AdaptiveThinkingInterceptor] but for WebClient (used in streaming).
 * Reads X-Gromozeka-* headers, removes them, and patches the serialized JSON body.
 */
class AdaptiveThinkingWebClientFilter : ExchangeFilterFunction {

    private val log = KLoggers.logger(this)
    private val objectMapper = ObjectMapper()
    private val bufferFactory = DefaultDataBufferFactory()

    override fun filter(request: ClientRequest, next: ExchangeFunction): Mono<ClientResponse> {
        val thinkingType = request.headers().getFirst(AdaptiveThinkingInterceptor.HEADER_THINKING_TYPE)
        val effort = request.headers().getFirst(AdaptiveThinkingInterceptor.HEADER_EFFORT)

        if (thinkingType == null && effort == null) {
            return next.exchange(request)
        }

        val modifiedRequest = ClientRequest.from(request)
            .headers { headers ->
                // Remove custom headers — they must NOT reach Anthropic
                headers.remove(AdaptiveThinkingInterceptor.HEADER_THINKING_TYPE)
                headers.remove(AdaptiveThinkingInterceptor.HEADER_EFFORT)
            }
            .body(wrapBodyInserter(request.body(), thinkingType, effort))
            .build()

        return next.exchange(modifiedRequest)
    }

    private fun wrapBodyInserter(
        originalBody: BodyInserter<*, in ClientHttpRequest>,
        thinkingType: String?,
        effort: String?
    ): BodyInserter<*, in ClientHttpRequest> {
        return BodyInserter<Any, ClientHttpRequest> { outputMessage, context ->
            val decoratedRequest = object : ClientHttpRequestDecorator(outputMessage) {
                override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                    val modifiedBody = Flux.from(body)
                        .collectList()
                        .flatMapMany { buffers ->
                            try {
                                val totalSize = buffers.sumOf { it.readableByteCount() }
                                val combined = ByteArray(totalSize)
                                var offset = 0
                                buffers.forEach { buffer ->
                                    val count = buffer.readableByteCount()
                                    buffer.read(combined, offset, count)
                                    offset += count
                                    DataBufferUtils.release(buffer)
                                }

                                val patched = patchBody(combined, thinkingType, effort)
                                Flux.just(bufferFactory.wrap(patched))
                            } catch (e: Exception) {
                                log.warn(e) { "Failed to patch streaming request body, using original" }
                                Flux.fromIterable(buffers)
                            }
                        }

                    return super.writeWith(modifiedBody)
                }
            }

            @Suppress("UNCHECKED_CAST")
            (originalBody as BodyInserter<Any, in ClientHttpRequest>).insert(decoratedRequest, context)
        }
    }

    private fun patchBody(body: ByteArray, thinkingType: String?, effort: String?): ByteArray {
        if (body.isEmpty()) return body

        return try {
            val json = objectMapper.readTree(body) as? ObjectNode ?: return body

            if (thinkingType == "adaptive") {
                val thinkingNode = objectMapper.createObjectNode()
                thinkingNode.put("type", "adaptive")
                json.set<ObjectNode>("thinking", thinkingNode)
            }

            if (effort != null) {
                val outputConfigNode = objectMapper.createObjectNode()
                outputConfigNode.put("effort", effort)
                json.set<ObjectNode>("output_config", outputConfigNode)
            }

            objectMapper.writeValueAsBytes(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to patch request body for adaptive thinking, using original" }
            body
        }
    }
}
