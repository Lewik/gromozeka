package com.gromozeka.worker

import com.gromozeka.application.config.ApplicationCoroutineConfiguration
import com.gromozeka.application.service.AutoApproveToolApprovalService
import com.gromozeka.application.service.DefaultCommandMonitorService
import com.gromozeka.application.service.DefaultCommandTaskService
import com.gromozeka.application.service.DirectAiRequestResponseExecutionHandler
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.application.service.SettingsService
import com.gromozeka.infrastructure.ai.config.InternalMcpToolsRegistrar
import com.gromozeka.shared.logging.JvmLogComponent
import com.gromozeka.shared.logging.JvmLogDirectoryResolver
import kotlinx.coroutines.runBlocking
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "configure") {
        runCatching {
            val bootstrap = System.`in`.bufferedReader().use { it.readText() }
            require(bootstrap.isNotBlank()) { "Worker bootstrap is required on stdin" }
            WorkerEnrollmentClient().configure(args.drop(1), bootstrap)
        }.onSuccess { configPath ->
            println("Worker configuration saved to $configPath")
        }.onFailure { error ->
            System.err.println("Worker configuration failed: ${error.message}")
            exitProcess(2)
        }
        return
    }
    if (args.firstOrNull() == "connect") {
        runCatching {
            WorkerDeviceConnectionClient().connect(args.drop(1))
        }.onSuccess { configPath ->
            println("Worker connected. Configuration saved to $configPath")
        }.onFailure { error ->
            System.err.println("Worker connection failed: ${error.message}")
            exitProcess(2)
        }
        return
    }
    if (args.firstOrNull() == "enroll") {
        runCatching {
            WorkerEnrollmentClient().enroll(args.drop(1))
        }.onSuccess { configPath ->
            println("Worker enrollment completed. Configuration saved to $configPath")
        }.onFailure { error ->
            System.err.println("Worker enrollment failed: ${error.message}")
            exitProcess(2)
        }
        return
    }

    applyWorkerSystemProperties(args)

    val context = SpringApplicationBuilder(GromozekaWorkerApplication::class.java)
        .web(WebApplicationType.NONE)
        .headless(GraphicsEnvironment.isHeadless())
        .profiles(resolveSpringProfile())
        .run(*args)

    val gateway = context.getBean(WorkerGatewayClient::class.java)
    check(gateway.isRunning) { "Worker Gateway did not start" }
    val failure = runBlocking { gateway.awaitTermination() }
    context.close()
    if (failure != null) {
        throw IllegalStateException("Conversation runtime Worker terminated unexpectedly", failure)
    }
}

private fun applyWorkerSystemProperties(args: Array<String>) {
    val mode = System.getProperty("GROMOZEKA_MODE")
        ?: System.getenv("GROMOZEKA_MODE")

    mode?.let { System.setProperty("GROMOZEKA_MODE", it) }
    if (System.getProperty("gromozeka.runtime.worker.version") == null &&
        System.getenv("GROMOZEKA_RUNTIME_WORKER_VERSION").isNullOrBlank()
    ) {
        System.setProperty("gromozeka.runtime.worker.version", currentWorkerVersion())
    }
    JvmLogDirectoryResolver.configure(args, mode, JvmLogComponent.WORKER)
}

internal fun currentWorkerVersion(): String =
    GromozekaWorkerApplication::class.java.`package`.implementationVersion
        ?.takeIf(String::isNotBlank)
        ?: "0.0.0-dev"

private fun resolveSpringProfile(): String =
    when ((System.getProperty("GROMOZEKA_MODE") ?: System.getenv("GROMOZEKA_MODE"))?.lowercase()) {
        "dev", "development" -> "dev"
        "test", "e2e" -> "e2e"
        null, "prod", "production" -> "prod"
        else -> error("Unsupported GROMOZEKA_MODE=${System.getProperty("GROMOZEKA_MODE")}")
    }

@SpringBootApplication
@ComponentScan(
    basePackages = [
        "com.gromozeka.worker",
        "com.gromozeka.infrastructure.ai",
    ],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["com\\.gromozeka\\.infrastructure\\.ai\\.mcp\\.tools\\..*"]
        ),
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [InternalMcpToolsRegistrar::class]
        ),
    ],
)
@Import(
    ApplicationCoroutineConfiguration::class,
    AutoApproveToolApprovalService::class,
    DefaultCommandMonitorService::class,
    DefaultCommandTaskService::class,
    DirectAiRequestResponseExecutionHandler::class,
    ParallelToolExecutor::class,
    SettingsService::class,
)
class GromozekaWorkerApplication(
    settingsService: SettingsService,
) {
    init {
        System.setProperty("GROMOZEKA_HOME", settingsService.gromozekaHome.absolutePath)
    }
}
