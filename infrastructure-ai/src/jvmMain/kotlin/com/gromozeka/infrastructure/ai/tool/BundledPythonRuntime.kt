package com.gromozeka.infrastructure.ai.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

object BundledPythonRuntime {
    private val lockBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/script-sdk/python-runtime.json")) {
            "Bundled Python runtime lock is missing"
        }.use { it.readBytes() }
    }
    private val lock: JsonObject by lazy { Json.parseToJsonElement(lockBytes.decodeToString()).jsonObject }
    private val lockHash: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(lockBytes).joinToString("") { "%02x".format(it) }
    }

    fun target(
        osName: String = System.getProperty("os.name"),
        architecture: String = System.getProperty("os.arch"),
    ): String {
        val os = osName.lowercase()
        val arch = architecture.lowercase()
        return when {
            os.contains("mac") && arch in setOf("aarch64", "arm64") -> "macos-arm64"
            os.contains("linux") && arch in setOf("amd64", "x86_64", "x64") -> "linux-x64"
            os.contains("windows") && arch in setOf("amd64", "x86_64", "x64") -> "windows-x64"
            else -> error("Bundled Python is not supported on $osName/$architecture")
        }
    }

    fun executable(
        installationHome: String? = System.getenv("GROMOZEKA_INSTALLATION_HOME"),
        projectRoot: String? = System.getProperty("gromozeka.project.root"),
        target: String = target(),
    ): File {
        val root = when {
            !projectRoot.isNullOrBlank() -> File(projectRoot, "build/python-runtime/$target/$lockHash")
            !installationHome.isNullOrBlank() -> File(installationHome, "runtime/python")
            else -> error("Bundled Python location is unavailable; use a Worker distribution or preparePythonRuntime in a development checkout")
        }
        val markerFile = File(root, "gromozeka-runtime.json")
        check(markerFile.isFile) {
            "Bundled Python is not prepared at ${root.absolutePath}; build it with preparePythonRuntime before starting this Worker"
        }
        val marker = Json.parseToJsonElement(markerFile.readText()).jsonObject
        check(marker["format"]?.jsonPrimitive?.content == "1" &&
            marker["lock_sha256"]?.jsonPrimitive?.content == lockHash &&
            marker["target"]?.jsonPrimitive?.content == target &&
            marker["python_version"] == lock["python_version"]) {
            "Bundled Python runtime does not match this Worker"
        }
        val configuration = checkNotNull(lock["targets"]?.jsonObject?.get(target)) {
            "Bundled Python lock does not contain target $target"
        }.jsonObject
        val executable = File(root, configuration.getValue("executable").jsonPrimitive.content).canonicalFile
        check(executable.isFile && executable.canExecute()) { "Bundled Python executable is missing or not executable: $executable" }
        return executable
    }
}
