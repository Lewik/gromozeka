package com.gromozeka.e2e

import com.gromozeka.presentation.AppComponents
import com.gromozeka.presentation.RemoteAppComponents
import com.gromozeka.presentation.RemoteAuthenticationConnection
import com.gromozeka.presentation.createRemoteAppComponents
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.RemoteAuthenticationInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal object E2eEnvironment {
    private var runtime: Runtime? = null

    init {
        java.lang.Runtime.getRuntime().addShutdownHook(
            Thread({ synchronized(this) { runtime?.close() } }, "gromozeka-e2e-shutdown")
        )
    }

    fun openClient(): E2eClient = synchronized(this) {
        val activeRuntime = runtime ?: Runtime.start().also { runtime = it }
        activeRuntime.openClient()
    }

    private class Runtime(
        private val database: PostgresTestDatabase,
        private val server: GromozekaServerProcess,
        private val artifactsDirectory: Path,
    ) : AutoCloseable {
        private val username = "compose-e2e"
        private val password = "compose-e2e-password"

        init {
            RemoteAuthenticationConnection(server.remoteUrl, "Compose E2E bootstrap").use { connection ->
                runBlocking {
                    val status = connection.status()
                    check(!status.initialized) { "Fresh E2E database was already initialized" }
                    connection.authenticate(
                        initialized = false,
                        input = RemoteAuthenticationInput(
                            username = username,
                            password = password,
                            displayName = "Compose E2E",
                            bootstrapToken = server.bootstrapToken,
                        ),
                    )
                }
            }
        }

        fun openClient(): E2eClient {
            val clientId = UUID.randomUUID().toString()
            val connection = RemoteAuthenticationConnection(server.remoteUrl, "Compose E2E $clientId")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val homeDirectory = artifactsDirectory.resolve("clients/$clientId")
            Files.createDirectories(homeDirectory)

            return try {
                val app = runBlocking {
                    connection.authenticate(
                        initialized = true,
                        input = RemoteAuthenticationInput(username = username, password = password),
                    )
                    val user = checkNotNull(connection.status().authenticatedUser) {
                        "E2E client login did not create an authenticated session"
                    }
                    createRemoteAppComponents(
                        remoteUrl = server.remoteUrl,
                        authenticatedUser = user,
                        scope = scope,
                        clientHomeDirectory = homeDirectory.toString(),
                        clientPlatform = ClientPlatform.DESKTOP,
                        httpClient = connection.httpClient,
                    )
                }
                E2eClient(app.components, app, connection, scope)
            } catch (error: Throwable) {
                scope.cancel()
                connection.close()
                throw error
            }
        }

        override fun close() {
            server.close()
            database.close()
        }

        companion object {
            fun start(): Runtime {
                val artifactsRoot = Path.of(
                    System.getProperty("gromozeka.e2e.artifactsDir")
                        ?: error("gromozeka.e2e.artifactsDir is not configured")
                )
                val processDirectory = artifactsRoot.resolve("process-${ProcessHandle.current().pid()}")
                Files.createDirectories(processDirectory)
                val database = PostgresTestDatabase.create()
                return try {
                    val server = GromozekaServerProcess.start(database, processDirectory)
                    Runtime(database, server, processDirectory)
                } catch (error: Throwable) {
                    database.close()
                    throw error
                }
            }
        }
    }
}

internal class E2eClient(
    val components: AppComponents,
    private val app: RemoteAppComponents,
    private val connection: RemoteAuthenticationConnection,
    private val scope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        app.close()
        scope.cancel()
        connection.close()
    }
}
