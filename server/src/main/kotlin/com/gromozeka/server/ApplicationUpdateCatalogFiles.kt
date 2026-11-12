package com.gromozeka.server

import com.gromozeka.application.service.SettingsService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ApplicationUpdateCatalogSource
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File

@Service
internal class ApplicationUpdateCatalogFiles(settings: SettingsService) : ApplicationUpdateCatalogSource {
    private val root = File(System.getenv("GROMOZEKA_UPDATES_DIRECTORY") ?: File(settings.gromozekaHome, "updates").path).canonicalFile
    private val version = GromozekaServerApplication::class.java.`package`.implementationVersion ?: "0.0.0-dev"
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: Pair<Long, ApplicationUpdateCatalog?>? = null

    @Synchronized
    override fun current(): ApplicationUpdateCatalog? {
        cached?.takeIf { System.nanoTime() - it.first < 30_000_000_000L }?.let { return it.second }
        val path = File(root, "$version/catalog.json")
        val catalog = if (!path.exists()) null else {
            require(path.parentFile.canonicalFile.parentFile == root)
            require(path.isFile && path.length() in 1..65536 && path.canonicalFile.parentFile == File(root, version).canonicalFile)
            json.decodeFromString<ApplicationUpdateCatalog>(path.readText()).also { require(it.version == version) }
        }
        cached = System.nanoTime() to catalog
        return catalog
    }

    fun artifact(artifact: ApplicationUpdateArtifact): File {
        val directory = File(root, artifact.version).canonicalFile
        require(directory.parentFile == root) { "Invalid release directory" }
        val file = File(directory, artifact.fileName).canonicalFile
        require(file.parentFile == directory && file.isFile && file.length() == artifact.size) { "Update package is unavailable" }
        return file
    }
}
