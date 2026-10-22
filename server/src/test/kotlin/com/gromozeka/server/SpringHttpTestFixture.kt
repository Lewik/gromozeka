package com.gromozeka.server

import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.PersonalAccessTokenService
import jakarta.servlet.Filter
import jakarta.servlet.http.Cookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/** Actual MVC dispatch and the production SecurityFilterChain, without sockets or a DB. */
internal class SpringHttpTestFixture(
    vararg controllers: Any,
    authentication: AuthenticationService = Mockito.mock(AuthenticationService::class.java),
    tokens: PersonalAccessTokenService = Mockito.mock(PersonalAccessTokenService::class.java),
    workers: WorkerGatewayAuthenticationService = WorkerGatewayAuthenticationService(Mockito.mock(WorkerEnrollmentRepository::class.java)),
    settings: ServerHttpSettings = ServerHttpSettings(),
    configurations: Array<Class<*>> = emptyArray(),
) : AutoCloseable {
    private val context = AnnotationConfigWebApplicationContext()
    val mvc: MockMvc
    init {
        context.servletContext = MockServletContext()
        context.register(MvcConfiguration::class.java, GromozekaHttpSecurityConfiguration::class.java, *configurations)
        context.addBeanFactoryPostProcessor { beans ->
            beans.registerSingleton("testAuthentication", authentication)
            beans.registerSingleton("testTokens", tokens)
            beans.registerSingleton("testWorkers", workers)
            beans.registerSingleton("testHttpSettings", settings)
            beans.registerSingleton("testHttpErrors", GromozekaHttpExceptionHandler())
            controllers.forEachIndexed { index, controller -> beans.registerSingleton("testController$index", controller) }
        }
        context.refresh()
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java))
            .build()
    }

    suspend fun perform(request: MockHttpServletRequestBuilder): MockHttpServletResponse = withContext(Dispatchers.IO) {
        val result = mvc.perform(request).andReturn()
        if (result.request.isAsyncStarted) {
            result.getAsyncResult(10_000)
            mvc.perform(asyncDispatch(result)).andReturn().response
        } else result.response
    }

    override fun close() { context.close() }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    internal class MvcConfiguration
}

internal fun MockHttpServletRequestBuilder.secureTestRequest(session: String? = null): MockHttpServletRequestBuilder =
    secure(true).with { request ->
        request.scheme = "https"
        request.serverName = "localhost"
        request.serverPort = 443
        request.remoteAddr = "203.0.113.10"
        request.addHeader("Host", "localhost")
        if (session != null) request.setCookies(Cookie(SESSION_COOKIE_NAME, session))
        request
    }
