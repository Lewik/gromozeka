package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.BundledMcpRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

internal object BundledMcpRuntimeResolver {
    fun resolve(runtime: BundledMcpRuntime): BundledMcpProcess = when (runtime) {
        BundledMcpRuntime.BROWSER_USE -> resolveBrowserUse()
    }

    private fun resolveBrowserUse(): BundledMcpProcess {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val launcherName = if (windows) "gromozeka-browser-mcp.cmd" else "gromozeka-browser-mcp"
        val explicitLauncher = System.getenv(BROWSER_MCP_LAUNCHER_ENV)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(Path::of)
        val projectRoot = System.getProperty(PROJECT_ROOT_PROPERTY)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(Path::of)
        val launcher = explicitLauncher
            ?: projectRoot?.resolve("deploy/distribution/$launcherName")
            ?: error(
                "Bundled Browser Use runtime is unavailable. " +
                    "Start Browser Use on a standalone Worker distribution or set $BROWSER_MCP_LAUNCHER_ENV."
            )
        require(Files.isRegularFile(launcher)) {
            "Bundled Browser Use launcher was not found: ${launcher.absolutePathString()}"
        }

        val environment = if (explicitLauncher == null && projectRoot != null) {
            mapOf(
                BROWSER_MCP_HOME_ENV to projectRoot.resolve("browser-mcp").absolutePathString(),
                RUNTIME_BOOTSTRAP_ENV to projectRoot.resolve(
                    if (windows) {
                        "deploy/distribution/runtime-bootstrap.ps1"
                    } else {
                        "deploy/distribution/runtime-bootstrap.sh"
                    }
                ).absolutePathString(),
            )
        } else {
            emptyMap()
        }

        return if (windows) {
            BundledMcpProcess(
                command = System.getenv("ComSpec") ?: "cmd.exe",
                arguments = listOf("/d", "/s", "/c", launcher.absolutePathString()),
                environment = environment,
            )
        } else {
            if (Files.isExecutable(launcher)) {
                BundledMcpProcess(
                    command = launcher.absolutePathString(),
                    environment = environment,
                )
            } else {
                BundledMcpProcess(
                    command = "/bin/bash",
                    arguments = listOf(launcher.absolutePathString()),
                    environment = environment,
                )
            }
        }
    }
}

internal data class BundledMcpProcess(
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)

private const val PROJECT_ROOT_PROPERTY = "gromozeka.project.root"
private const val BROWSER_MCP_LAUNCHER_ENV = "GROMOZEKA_BROWSER_MCP_LAUNCHER"
private const val BROWSER_MCP_HOME_ENV = "GROMOZEKA_BROWSER_MCP_HOME"
private const val RUNTIME_BOOTSTRAP_ENV = "GROMOZEKA_RUNTIME_BOOTSTRAP"
