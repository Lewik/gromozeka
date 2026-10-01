package com.gromozeka.domain.service

import com.gromozeka.domain.model.User
import com.gromozeka.domain.tool.ToolCancellationSignal
import kotlinx.serialization.Serializable

/** Privileged Runtime administration, not a read-only query API or a SQL sandbox. */
interface RuntimeSqlService {
    suspend fun execute(
        actor: User.Id,
        request: RuntimeSqlRequest,
        cancellation: ToolCancellationSignal = ToolCancellationSignal.None,
    ): RuntimeSqlResult
}

data class RuntimeSqlRequest(
    val sql: String,
    val maxRows: Int = 1_000,
    val timeoutSeconds: Int = 0,
) {
    init {
        require(sql.isNotBlank() && sql.length <= 1_000_000) { "SQL must contain 1..1000000 characters" }
        require(maxRows in 1..10_000) { "max_rows must be between 1 and 10000" }
        require(timeoutSeconds in 0..86_400) { "timeout_seconds must be between 0 and 86400" }
    }
}

@Serializable
data class RuntimeSqlResult(
    val results: List<RuntimeSqlStatementResult>,
    val outputTruncated: Boolean,
    val openTransactionRolledBack: Boolean,
)

@Serializable
data class RuntimeSqlStatementResult(
    val columns: List<RuntimeSqlColumn> = emptyList(),
    /** PostgreSQL text values preserve numeric precision; SQL NULL remains null. */
    val rows: List<List<String?>> = emptyList(),
    val updateCount: Long? = null,
    val rowsTruncated: Boolean = false,
    val cellsTruncated: Boolean = false,
)

@Serializable
data class RuntimeSqlColumn(val name: String, val databaseType: String)

class RuntimeSqlAccessDeniedException(message: String) : IllegalStateException(message)

/** Earlier statements may already have committed. Never automatically retry this operation. */
class RuntimeSqlExecutionException(val sqlState: String?, message: String) : IllegalStateException(message)
