package com.gromozeka.presentation.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal class MacOsLocalWorkerRuntime(
    userHome: Path,
    environment: Map<String, String>,
) : BaseDesktopLocalWorkerRuntime(userHome, environment) {
    override val deviceDisplayName: String = "Mac"
    override val workerIdSuffix: String = "mac"
    override val computerUsePermissionsSupported: Boolean = true

    private val launchAgent = userHome.resolve("Library/LaunchAgents/com.gromozeka.worker.plist")
    private val stableLauncher = userHome.resolve(
        "Library/Application Support/Gromozeka/worker/launcher/Gromozeka Worker.app/Contents/MacOS/Gromozeka Worker"
    )

    override suspend fun isEnabled(): Boolean = Files.isRegularFile(launchAgent)

    override suspend fun isRunning(): Boolean =
        runCatching { runService("status", requireSuccess = false).exitCode == 0 }
            .getOrDefault(false)

    override suspend fun enable() {
        runService("install")
    }

    override suspend fun disable() {
        if (Files.exists(launchAgent)) {
            runService("uninstall")
        }
    }

    override suspend fun start() {
        runService("start")
    }

    override suspend fun stop() {
        if (Files.isRegularFile(launchAgent)) {
            runService("stop")
        }
    }

    override suspend fun enroll(arguments: List<String>, enrollmentToken: String) {
        val launcher = bundleRoot().resolve("bin/gromozeka-worker")
        runCommand(
            command = listOf("/bin/bash", launcher.toString()) + arguments,
            requiredFile = launcher,
            description = "Local Worker ${arguments.firstOrNull().orEmpty()}",
            enrollmentToken = enrollmentToken,
        )
    }

    override suspend fun readComputerUsePermissions(): LocalWorkerPermissions {
        if (!Files.isExecutable(stableLauncher)) return LocalWorkerPermissions()
        val result = runCatching { runService("permissions-status", requireSuccess = false) }.getOrNull()
            ?: return LocalWorkerPermissions()
        if (result.exitCode != 0) return LocalWorkerPermissions()
        return runCatching {
            val values = Json.parseToJsonElement(result.output).jsonObject
            LocalWorkerPermissions(
                screenRecording = values["screenRecording"].permissionState(),
                accessibility = values["accessibility"].permissionState(),
            )
        }.getOrDefault(LocalWorkerPermissions())
    }

    override suspend fun requestComputerUsePermissions() {
        runService("open-permissions")
    }

    override fun requireStableInstallationPath() {
        val resourcesDirectory = applicationResourcesDirectory() ?: return
        require(!resourcesDirectory.toAbsolutePath().startsWith(Path.of("/Volumes"))) {
            "Move Gromozeka to Applications before enabling the Local Worker"
        }
    }

    override fun hostName(): String? = environment["HOSTNAME"]

    private suspend fun runService(command: String, requireSuccess: Boolean = true): DesktopCommandResult {
        val service = bundleRoot().resolve("bin/gromozeka-worker-service")
        return runCommand(
            command = listOf("/bin/bash", service.toString(), command),
            requiredFile = service,
            description = "Local Worker command: $command",
            requireSuccess = requireSuccess,
        )
    }

    private fun JsonElement?.permissionState(): LocalWorkerPermissionState =
        when (this?.jsonPrimitive?.booleanOrNull) {
            true -> LocalWorkerPermissionState.GRANTED
            false -> LocalWorkerPermissionState.NOT_GRANTED
            null -> LocalWorkerPermissionState.UNKNOWN
        }
}
