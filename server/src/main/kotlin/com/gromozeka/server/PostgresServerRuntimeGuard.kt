package com.gromozeka.server

import klog.KLoggers
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import javax.sql.DataSource

@Service
@DependsOn("postgresFlyway")
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PostgresServerRuntimeGuard(
    private val dataSource: DataSource,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val lifecycleLock = Any()
    private var lockConnection: Connection? = null

    override fun start() {
        synchronized(lifecycleLock) {
            if (lockConnection != null) return
            val connection = dataSource.connection
            try {
                val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                    statement.setLong(1, SERVER_RUNTIME_ADVISORY_LOCK_ID)
                    statement.executeQuery().use { result ->
                        check(result.next()) { "PostgreSQL did not return an advisory lock result" }
                        result.getBoolean(1)
                    }
                }
                check(acquired) {
                    "Another Gromozeka Server is already active for this PostgreSQL runtime database"
                }
                lockConnection = connection
                log.info { "Acquired PostgreSQL Server runtime lock" }
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            val connection = lockConnection ?: return
            lockConnection = null
            try {
                if (!connection.isClosed) {
                    connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                        statement.setLong(1, SERVER_RUNTIME_ADVISORY_LOCK_ID)
                        statement.execute()
                    }
                }
            } finally {
                connection.close()
            }
            log.info { "Released PostgreSQL Server runtime lock" }
        }
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = synchronized(lifecycleLock) {
        lockConnection?.isClosed == false
    }

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 100
}

private const val SERVER_RUNTIME_ADVISORY_LOCK_ID = 0x47524F4D4F5A454BL
