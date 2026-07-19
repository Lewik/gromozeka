package com.gromozeka.infrastructure.db.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

class FileSystemAgentScannerTest {
    @Test
    fun returnsNullWhenNoProjectRootExists() {
        val originalUserDirectory = System.getProperty("user.dir")
        val originalProjectRoot = System.getProperty("gromozeka.project.root")
        val startDirectory = Files.createTempDirectory("gromozeka-agent-scanner").toFile()

        try {
            System.setProperty("user.dir", startDirectory.absolutePath)
            System.clearProperty("gromozeka.project.root")

            assertNull(FileSystemAgentScanner().findProjectRoot())
        } finally {
            System.setProperty("user.dir", originalUserDirectory)
            if (originalProjectRoot == null) {
                System.clearProperty("gromozeka.project.root")
            } else {
                System.setProperty("gromozeka.project.root", originalProjectRoot)
            }
            startDirectory.deleteRecursively()
        }
    }
}
