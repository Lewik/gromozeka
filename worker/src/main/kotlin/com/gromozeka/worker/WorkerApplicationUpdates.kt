package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerUpdateParticipant
import com.gromozeka.application.service.SettingsService
import com.gromozeka.domain.model.UpdateComponent
import com.gromozeka.updates.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.File

@Component
@ConditionalOnProperty(name = ["gromozeka.worker-gateway.enabled"], havingValue = "true")
class WorkerApplicationUpdates(
    private val properties: WorkerGatewayProperties,
    private val gateway: WorkerGatewayClient,
    private val settings: SettingsService,
    private val updateParticipants: List<WorkerUpdateParticipant>,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) : SmartLifecycle {
    private var agent: ApplicationUpdateAgent? = null
    private var http: HttpClient? = null

    override fun start() {
        if (agent != null) return
        val client = HttpClient(CIO) {
            properties.caCertificatePath?.takeIf(String::isNotBlank)?.let { path -> engine { https { trustManager = workerTrustManager(path) } } }
        }
        http = client
        val home = System.getenv("GROMOZEKA_INSTALLATION_HOME")?.let(::File)
            ?: if (System.getProperty("os.name").startsWith("Windows")) File(System.getenv("ProgramFiles"), "Gromozeka Worker") else null
        val preparation = WorkerUpdatePreparation(gateway.updateGate, updateParticipants)
        val installer = DesktopUpdateInstaller(UpdateComponent.WORKER, home,
            prepareUpdate = preparation::prepare, resumeWork = preparation::resume)
        agent = ApplicationUpdateAgent(properties.serverUrl, client, UpdateComponent.WORKER, DesktopUpdateInstaller.platform(),
            home?.name ?: "Worker", currentWorkerVersion(),
            UpdateLocalStore(File(settings.gromozekaHome, "updates/${UpdateLocalStore.key(properties.serverUrl)}")), installer,
            credential = properties.credential).also { it.start(scope) }
    }

    override fun stop() { agent?.close(); agent = null; http?.close(); http = null }
    override fun isRunning() = agent != null
    override fun getPhase() = 110
}
