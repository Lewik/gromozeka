package com.gromozeka.infrastructure.ai.diagnostics

import com.openai.core.RequestOptions
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpRequestBody
import com.openai.core.http.HttpResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream
import java.util.concurrent.CompletableFuture

internal class AiTrafficDumpOpenAiClient(private val delegate: HttpClient) : HttpClient {
    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val trace = start(request) ?: return delegate.execute(request, requestOptions)
        return try { captured(delegate.execute(captured(request, trace), requestOptions), trace) }
        catch (error: Throwable) { trace.failure(error); throw error }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> {
        val trace = start(request) ?: return delegate.executeAsync(request, requestOptions)
        val pending = try { delegate.executeAsync(captured(request, trace), requestOptions) }
        catch (error: Throwable) { trace.failure(error); throw error }
        val result = object : CompletableFuture<HttpResponse>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                pending.cancel(mayInterruptIfRunning)
                return super.cancel(mayInterruptIfRunning)
            }
        }
        pending.whenComplete { response, error ->
            if (error != null) { trace.failure(error); result.completeExceptionally(error) }
            else if (!result.complete(captured(response, trace))) response.close()
        }
        return result
    }

    override fun close() = delegate.close()

    private fun start(request: HttpRequest) = DangerousAiTrafficDump.open("openai", "sdk-http")?.also {
        it.headers("request", request.headers.names().associateWith(request.headers::values),
            mapOf("baseUrl" to request.baseUrl.orEmpty(), "path" to request.pathSegments.joinToString("/"), "method" to request.method.name))
        it.event("request.query", JsonObject(request.queryParams.keys().associateWith { name ->
            JsonArray(request.queryParams.values(name).map(::JsonPrimitive))
        }))
    }

    private fun captured(request: HttpRequest, trace: AiTrafficRecording): HttpRequest {
        val body = request.body ?: return request
        return request.toBuilder().body(object : HttpRequestBody by body {
            override fun writeTo(outputStream: OutputStream) {
                body.writeTo(trace.output("request", outputStream))
                trace.event("request.complete")
            }
        }).build()
    }

    private fun captured(response: HttpResponse, trace: AiTrafficRecording): HttpResponse {
        trace.headers("response", response.headers().names().associateWith(response.headers()::values),
            mapOf("status" to response.statusCode().toString()))
        val body = trace.input("response", response.body())
        return object : HttpResponse by response {
            override fun body() = body
            override fun close() { try { response.close() } finally { trace.event("response.closed") } }
        }
    }
}
