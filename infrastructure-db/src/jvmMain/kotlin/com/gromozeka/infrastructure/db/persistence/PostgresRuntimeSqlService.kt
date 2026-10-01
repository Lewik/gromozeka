package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.RuntimeSqlAccessDeniedException
import com.gromozeka.domain.service.RuntimeSqlColumn
import com.gromozeka.domain.service.RuntimeSqlExecutionException
import com.gromozeka.domain.service.RuntimeSqlRequest
import com.gromozeka.domain.service.RuntimeSqlResult
import com.gromozeka.domain.service.RuntimeSqlService
import com.gromozeka.domain.service.RuntimeSqlStatementResult
import com.gromozeka.domain.tool.ToolCancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.postgresql.core.BaseConnection
import org.postgresql.core.TransactionState
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** Session lock survives user-supplied COMMIT; account changes take the matching transaction lock. */
internal const val RUNTIME_LOGIN_GATE_KEY =
    "hashtextextended(current_database() || ':' || current_schema() || ':gromozeka-runtime-logins', 0)"

@Service
class PostgresRuntimeSqlService(
    @Qualifier("runtimeSqlDataSource") private val dataSource: DataSource,
    @Value("\${gromozeka.postgres.schema:\${GROMOZEKA_POSTGRES_SCHEMA:public}}") private val schema: String = "public",
) : RuntimeSqlService {
    init { require(schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid Runtime SQL schema" } }
    override suspend fun execute(actor: User.Id, request: RuntimeSqlRequest, cancellation: ToolCancellationSignal): RuntimeSqlResult =
        withContext(Dispatchers.IO) {
            cancellation.throwIfCancellationRequested()
            // This is an unpooled physical connection. Arbitrary SET, temp objects, locks and
            // transaction state must never contaminate an ordinary application connection.
            dataSource.connection.use { connection ->
                coroutineScope {
                    val requestContext = coroutineContext
                    val requestCancellation = ToolCancellationSignal {
                        cancellation.throwIfCancellationRequested()
                        requestContext.ensureActive()
                    }
                    val active = AtomicReference<Statement?>()
                    val cancellationError = AtomicReference<Throwable?>()
                    val watcher = launch(Dispatchers.IO) {
                        try {
                            while (isActive) {
                                delay(100)
                                cancellation.throwIfCancellationRequested()
                            }
                        } catch (error: Throwable) {
                            cancellationError.compareAndSet(null, error)
                            runCatching { active.get()?.cancel() }
                        } finally {
                            // Parent cancellation can make the while condition false without
                            // throwing from delay. Still interrupt a currently blocked JDBC call.
                            if (!isActive) active.get()?.let { statement ->
                                cancellationError.compareAndSet(null, CancellationException("SQL invocation cancelled"))
                                runCatching { statement.cancel() }
                            }
                        }
                    }
                    try {
                        connection.autoCommit = true
                        if (connection.schema != schema) throw RuntimeSqlAccessDeniedException("Runtime SQL schema is unavailable")
                        connection.createStatement().use { guard ->
                            active.set(guard)
                            guard.queryTimeout = request.timeoutSeconds
                            guard.executeQuery("SELECT pg_advisory_lock($RUNTIME_LOGIN_GATE_KEY)").use { it.next() }
                            requestCancellation.throwIfCancellationRequested()
                            requireSingleOwner(guard, actor)
                        }
                        active.set(null)
                        cancellationError.get()?.let { throw it }
                        requestCancellation.throwIfCancellationRequested()
                        val output = executeStatements(connection, request, active, requestCancellation)
                        cancellationError.get()?.let { throw it }
                        requestCancellation.throwIfCancellationRequested()
                        output
                    } catch (error: SQLException) {
                        cancellationError.get()?.let { throw it }
                        requestCancellation.throwIfCancellationRequested()
                        throw RuntimeSqlExecutionException(error.sqlState,
                            "${error.message.orEmpty().take(8_192)}\nEarlier statements may have committed; do not retry automatically.")
                    } finally {
                        // Clear before cancelling the watcher so normal completion cannot cancel a finished statement.
                        active.set(null)
                        watcher.cancel()
                    }
                }
            }
        }

    private fun requireSingleOwner(statement: Statement, actor: User.Id) {
        statement.executeQuery("SELECT id, role FROM \"$schema\".users WHERE status = 'ACTIVE' AND login_allowed = true ORDER BY id LIMIT 2").use { rows ->
            if (!rows.next()) throw RuntimeSqlAccessDeniedException("SQL requires exactly one login-enabled user in this Runtime")
            val id = rows.getString("id")
            val owner = rows.getString("role") == User.Role.OWNER.name
            if (rows.next()) throw RuntimeSqlAccessDeniedException("SQL is unavailable while multiple users can log in")
            if (id != actor.value || !owner) throw RuntimeSqlAccessDeniedException("SQL is only available to the sole login-enabled Runtime owner")
        }
    }

    private fun executeStatements(
        connection: Connection,
        request: RuntimeSqlRequest,
        active: AtomicReference<Statement?>,
        cancellation: ToolCancellationSignal,
    ): RuntimeSqlResult {
        val results = mutableListOf<RuntimeSqlStatementResult>()
        val budget = OutputBudget()
        connection.createStatement().use { statement ->
            active.set(statement)
            statement.queryTimeout = request.timeoutSeconds
            // Do not use JDBC maxRows or rewrite SQL: SELECT may itself have side effects.
            // Bound the returned representation only; the submitted SQL executes unchanged.
            cancellation.throwIfCancellationRequested()
            var hasRows = statement.execute(request.sql)
            while (true) {
                cancellation.throwIfCancellationRequested()
                val result = if (hasRows) statement.resultSet.use { readRows(it, request.maxRows, budget) }
                else {
                    val count = statement.largeUpdateCount
                    if (count == -1L) break
                    RuntimeSqlStatementResult(updateCount = count)
                }
                if (results.size < MAX_RESULT_SETS) results += result else budget.truncated = true
                hasRows = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)
            }
        }
        active.set(null)
        val open = connection.unwrap(BaseConnection::class.java).transactionState != TransactionState.IDLE
        if (open) connection.createStatement().use { it.execute("ROLLBACK") }
        return RuntimeSqlResult(results, budget.truncated || results.any { it.rowsTruncated || it.cellsTruncated }, open)
    }

    private fun readRows(rows: ResultSet, maxRows: Int, budget: OutputBudget): RuntimeSqlStatementResult {
        val meta = rows.metaData
        val columns = (1..meta.columnCount).map { RuntimeSqlColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it)) }
        budget.remaining -= columns.sumOf { (it.name.length + it.databaseType.length) * 6 + 64 }
        val result = mutableListOf<List<String?>>()
        var truncated = false
        var cellsTruncated = false
        while (rows.next()) {
            if (result.size >= maxRows || budget.remaining <= 0) { truncated = true; break }
            result += (1..meta.columnCount).map { index ->
                rows.getString(index)?.let { value ->
                    val count = minOf(value.length, MAX_CELL_CHARACTERS, (budget.remaining / 6 - 8).coerceAtLeast(0))
                    if (count != value.length) cellsTruncated = true
                    budget.remaining -= count * 6 + 8
                    value.take(count)
                } ?: run { budget.remaining -= 8; null }
            }
        }
        return RuntimeSqlStatementResult(columns, result, rowsTruncated = truncated, cellsTruncated = cellsTruncated)
    }

    private class OutputBudget(var remaining: Int = MAX_OUTPUT_CHARACTERS, var truncated: Boolean = false)
    private companion object {
        const val MAX_OUTPUT_CHARACTERS = 1_000_000
        const val MAX_CELL_CHARACTERS = 16_384
        const val MAX_RESULT_SETS = 100
    }
}
