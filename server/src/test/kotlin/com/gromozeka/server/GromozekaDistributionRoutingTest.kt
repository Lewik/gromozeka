package com.gromozeka.server

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
}
