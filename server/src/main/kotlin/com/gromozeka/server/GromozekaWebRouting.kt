package com.gromozeka.server

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.EncodedResourceResolver
import org.springframework.web.servlet.resource.PathResourceResolver
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import java.security.MessageDigest

private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
private val contentHashedAssetName = Regex("""^[0-9a-f]{20,64}\.(wasm|js|css)$""")

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class GromozekaWebRouting {
    @Bean
    fun webResources(settings: ServerHttpSettings) = GromozekaWebResourceHandler().apply {
        setLocations(listOf(FileSystemResource(settings.webRoot.absolutePath.trimEnd('/') + "/")))
        setResourceResolvers(listOf(EncodedResourceResolver(), PathResourceResolver()))
        setEtagGenerator { resource ->
            val digest = MessageDigest.getInstance("SHA-256")
            resource.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            "\"" + digest.digest().joinToString("") { "%02x".format(it) } + "\""
        }
    }

    @Bean
    fun webResourcesMapping(webResources: GromozekaWebResourceHandler) = SimpleUrlHandlerMapping().apply {
        order = Int.MAX_VALUE - 2
        setUrlMap(mapOf<String, Any>("/**" to webResources))
    }
}

internal class GromozekaWebResourceHandler : ResourceHttpRequestHandler() {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val name = request.requestURI.substringAfterLast('/').removeSuffix(".br").removeSuffix(".gz")
        response.setHeader(HttpHeaders.CACHE_CONTROL,
            if (contentHashedAssetName.matches(name)) IMMUTABLE_CACHE_CONTROL else "no-cache")
        super.handleRequest(request, response)
    }
}

@Controller
internal class GromozekaWebIndex {
    @GetMapping("/")
    fun index(): String = "forward:/index.html"
}
