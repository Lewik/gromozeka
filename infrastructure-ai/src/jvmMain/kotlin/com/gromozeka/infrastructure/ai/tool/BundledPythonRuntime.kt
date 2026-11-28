package com.gromozeka.infrastructure.ai.tool

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class BundledPythonUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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
            else -> throw BundledPythonUnavailableException("Bundled Python is not supported on $osName/$architecture")
        }
    }

    fun executable(
        installationHome: String? = System.getenv("GROMOZEKA_INSTALLATION_HOME"),
        projectRoot: String? = System.getProperty("gromozeka.project.root"),
        target: String = target(),
    ): File {
        val configuration = checkNotNull(lock["targets"]?.jsonObject?.get(target)) {
            "Bundled Python lock does not contain target $target"
        }.jsonObject
        val executablePath = configuration.getValue("executable") as? JsonPrimitive
            ?: error("Bundled Python lock executable must be a string")
        check(executablePath.isString && executablePath.content.isNotBlank()) { "Bundled Python lock executable must be a nonblank string" }
        val expectedVersion = checkNotNull(lock["python_version"]) { "Bundled Python lock version is missing" }
        check(expectedVersion is JsonPrimitive && expectedVersion.isString && expectedVersion.content.isNotBlank()) {
            "Bundled Python lock version must be a nonblank string"
        }
        val expectedHash = lockHash
        val root = when {
            !projectRoot.isNullOrBlank() -> File(projectRoot, "build/python-runtime/$target/$expectedHash")
            !installationHome.isNullOrBlank() -> File(installationHome, "runtime/python")
            else -> throw BundledPythonUnavailableException("Bundled Python location is unavailable; use a Worker distribution or preparePythonRuntime in a development checkout")
        }
        val markerFile = File(root, "gromozeka-runtime.json")
        if (!markerFile.isFile) throw BundledPythonUnavailableException(
            "Bundled Python is not prepared at ${root.absolutePath}; build it with preparePythonRuntime before starting this Worker"
        )
        val marker = try {
            Json.parseToJsonElement(markerFile.readText()) as? JsonObject
                ?: throw BundledPythonUnavailableException("Bundled Python runtime marker must be an object: $markerFile")
        } catch (error: IOException) {
            throw BundledPythonUnavailableException("Bundled Python runtime marker cannot be read: $markerFile", error)
        } catch (error: SerializationException) {
            throw BundledPythonUnavailableException("Bundled Python runtime marker is invalid: $markerFile", error)
        }
        if ((marker["format"] as? JsonPrimitive)?.content != "1" ||
            (marker["lock_sha256"] as? JsonPrimitive)?.content != expectedHash ||
            (marker["target"] as? JsonPrimitive)?.content != target ||
            marker["python_version"] != expectedVersion) {
            throw BundledPythonUnavailableException("Bundled Python runtime does not match this Worker")
        }
        val executable = try {
            File(root, executablePath.content).canonicalFile
        } catch (error: IOException) {
            throw BundledPythonUnavailableException("Bundled Python executable cannot be resolved at ${root.absolutePath}", error)
        }
        if (!executable.isFile || !executable.canExecute()) {
            throw BundledPythonUnavailableException("Bundled Python executable is missing or not executable: $executable")
        }
        return executable
    }
}
