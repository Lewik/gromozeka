package com.gromozeka.server

import com.gromozeka.remote.protocol.DistributionComponent
import kotlin.test.Test
import kotlin.test.assertEquals

class GromozekaDistributionRoutingTest {
    @Test
    fun `tagged server resolves downloads from its exact release`() {
        assertEquals(
            "https://github.com/Lewik/gromozeka/releases/download/v2.3.4",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "2.3.4",
                releaseRepository = "Lewik/gromozeka",
                configuredDownloadBaseUrl = null,
            ),
        )
    }

    @Test
    fun `development server resolves downloads from latest release`() {
        assertEquals(
            "https://github.com/Lewik/gromozeka/releases/latest/download",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "0.0.0-dev",
                releaseRepository = "Lewik/gromozeka",
                configuredDownloadBaseUrl = null,
            ),
        )
    }

    @Test
    fun `prerelease server resolves downloads from its exact release`() {
        assertEquals(
            "https://github.com/Lewik/gromozeka/releases/download/v1.5.0-test.1",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "1.5.0-test.1",
                releaseRepository = "Lewik/gromozeka",
                configuredDownloadBaseUrl = null,
            ),
        )
    }

    @Test
    fun `configured mirror overrides GitHub release location`() {
        assertEquals(
            "https://downloads.example/gromozeka",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "2.3.4",
                releaseRepository = "ignored/repository",
                configuredDownloadBaseUrl = "https://downloads.example/gromozeka/",
            ),
        )
    }

    @Test
    fun `distribution catalog exposes each supported standalone server`() {
        val serverArtifacts = distributionArtifacts("https://downloads.example/gromozeka")
            .filter { it.component == DistributionComponent.SERVER }

        assertEquals(
            listOf(
                "gromozeka-server-macos-arm64.tar.gz",
                "gromozeka-server-windows-x64.zip",
                "gromozeka-server-linux-x64.tar.gz",
            ),
            serverArtifacts.map { it.fileName },
        )
        assertEquals(
            serverArtifacts.map { "https://downloads.example/gromozeka/${it.fileName}" },
            serverArtifacts.map { it.downloadUrl },
        )
    }
}
