package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.AiConfigurationProvider
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
internal class OpenAiSiwcStartupAuthService(
    private val configuration: AiConfigurationProvider,
    private val auth: OpenAiSiwcAuthService,
    private val browser: OpenAiSiwcBrowserAuthService,
    @Qualifier("applicationScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private var catalogJob: Job? = null

    @PostConstruct
    fun start() {
        catalogJob = scope.launch(Dispatchers.IO + CoroutineName("openai-siwc-auth")) {
            configuration.snapshotFlow
                .filterNotNull()
                .map { snapshot -> snapshot.catalog.connections.any {
                    (it.enabled || it.id in snapshot.runtimeEnabledConnectionIds) &&
                        it.kind == AiConnection.Kind.OPENAI_SIWC
                } }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) bootstrapIfNeeded() else browser.cancelLogin()
                }
        }
    }

    @PreDestroy
    fun stop() = runBlocking {
        catalogJob?.cancelAndJoin()
    }

    private fun bootstrapIfNeeded() {
        val snapshot = configuration.snapshot
        if (snapshot.catalog.connections.none {
                (it.enabled || it.id in snapshot.runtimeEnabledConnectionIds) &&
                    it.kind == AiConnection.Kind.OPENAI_SIWC
            }) return
        val session = auth.session()
        if (session?.status == OpenAiSiwcStoredState.Status.CONNECTED && session.credentials != null) {
            log.info("ChatGPT SIWC connection loaded")
            return
        }
        browser.beginLogin()
            .onSuccess {
                log.warn("OPENAI_SIWC is enabled but not authenticated. Open this URL and complete ChatGPT sign-in:")
                log.warn(it.url)
            }
            .onFailure { error -> log.error(error) { "Failed to start ChatGPT SIWC sign-in" } }
    }
}
