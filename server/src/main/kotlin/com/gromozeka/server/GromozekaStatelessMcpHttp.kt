package com.gromozeka.server

import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** MVC owns request cancellation; the SDK owns the stateless MCP HTTP protocol. */
@RestController
internal class GromozekaStatelessMcpHttp(private val factory: GromozekaMcpServerFactory) {
    @PostMapping("/mcp")
    suspend fun post(request: HttpServletRequest, response: HttpServletResponse) {
        val caller = request.getAttribute(AUTHENTICATED_MCP_CALLER) as? AuthenticatedMcpCaller
            ?: throw HttpAuthenticationException(HttpStatus.UNAUTHORIZED, "Authentication required")
        try {
            factory.create(caller).use { session ->
                val version = request.getHeader("MCP-Protocol-Version")
                if (version != null && version !in session.transport.protocolVersions()) {
                    response.writeHttpError(HttpStatus.BAD_REQUEST, "Unsupported MCP protocol version")
                    return
                }
                // Interrupting the MVC coroutine cancels the SDK's blocking subscription,
                // which in turn cancels the native suspend callback. It is not a retry.
                runInterruptible(Dispatchers.IO) {
                    session.transport.service(request as ServletRequest, response as ServletResponse)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!response.isCommitted) response.writeHttpError(HttpStatus.INTERNAL_SERVER_ERROR, "MCP request failed")
        }
    }

    @RequestMapping("/mcp", method = [RequestMethod.GET, RequestMethod.DELETE])
    fun unsupported(response: HttpServletResponse) {
        response.status = HttpStatus.METHOD_NOT_ALLOWED.value()
    }
}
