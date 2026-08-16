package com.gromozeka.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmLogDirectoryResolverTest {
    @Test
    fun standardCommandLinePathHasHighestPrecedence() {
        val path = resolve(
            args = listOf("--logging.file.path=/cli"),
            properties = mapOf("logging.file.path" to "/property"),
            environment = mapOf("GROMOZEKA_LOG_DIR" to "/environment"),
        )

        assertEquals("/cli", path)
    }

    @Test
    fun standardCommandLinePathSupportsSeparateValue() {
        val path = resolve(
            args = listOf("--server.port=9000", "--logging.file.path", "/cli"),
            properties = mapOf("logging.file.path" to "/property"),
        )

        assertEquals("/cli", path)
    }

    @Test
    fun unrelatedCommandLineArgumentIsIgnored() {
        val path = resolve(
            args = listOf("--server.port=9000"),
            properties = mapOf("logging.file.path" to "/property"),
        )

        assertEquals("/property", path)
    }

    @Test
    fun canonicalCommandLineDirectoryKeepsWorkerLogsSeparate() {
        val path = resolve(
            args = listOf("--gromozeka.log.dir=/diagnostics"),
            component = JvmLogComponent.WORKER,
        )

        assertEquals("/diagnostics/workers", path)
    }

    @Test
    fun canonicalDirectoryKeepsWorkerLogsSeparate() {
        val path = resolve(
            component = JvmLogComponent.WORKER,
            environment = mapOf("GROMOZEKA_LOG_DIR" to "/diagnostics"),
        )

        assertEquals("/diagnostics/workers", path)
    }

    @Test
    fun standardEnvironmentPathRemainsExact() {
        val path = resolve(
            component = JvmLogComponent.WORKER,
            environment = mapOf("LOGGING_FILE_PATH" to "/worker-only"),
        )

        assertEquals("/worker-only", path)
    }

    @Test
    fun productionDefaultsArePlatformSpecific() {
        assertEquals(
            "/home/test/Library/Logs/Gromozeka",
            resolve(operatingSystem = "Mac OS X"),
        )
        assertEquals(
            "/home/test/AppData/Local/Gromozeka/logs",
            resolve(operatingSystem = "Windows 11"),
        )
        assertEquals(
            "/home/test/.local/share/Gromozeka/logs",
            resolve(operatingSystem = "Linux"),
        )
    }

    private fun resolve(
        args: List<String> = emptyList(),
        component: JvmLogComponent = JvmLogComponent.SERVER,
        properties: Map<String, String> = emptyMap(),
        environment: Map<String, String> = emptyMap(),
        operatingSystem: String = "Linux",
    ): String = JvmLogDirectoryResolver.resolve(
        args = args,
        mode = "prod",
        component = component,
        systemProperties = properties,
        environment = environment,
        userHome = "/home/test",
        operatingSystem = operatingSystem,
    )
}
