package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.service.CommandScriptTokens
import com.gromozeka.domain.service.CommandTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

internal data class CommandScriptGrant(
    val origin: BackgroundActivityOrigin,
    val projectId: Project.Id,
    val tokenHash: String,
    val createdAt: Instant,
    val checkedAt: Instant,
    val lastReminderDay: Long,
    val revokedAt: Instant?,
)

internal interface CommandScriptGrantRepository {
    suspend fun register(origin: BackgroundActivityOrigin, projectId: Project.Id, tokenHash: String, at: Instant): CommandScriptGrant
    suspend fun findByHash(tokenHash: String): CommandScriptGrant?
    suspend fun revoke(conversationId: Conversation.Id, taskId: CommandTask.Id, at: Instant)
    suspend fun maintenanceCandidates(checkedBefore: Instant, limit: Int): List<CommandScriptGrant>
    suspend fun markChecked(taskId: CommandTask.Id, at: Instant, reminderDay: Long)
    suspend fun deleteRevokedBefore(before: Instant, limit: Int): Int
}

@Service
@DependsOn("postgresFlyway")
internal class PostgresCommandScriptGrantRepository(private val dataSource: DataSource) : CommandScriptGrantRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun register(origin: BackgroundActivityOrigin, projectId: Project.Id, tokenHash: String, at: Instant): CommandScriptGrant = transaction { c ->
        require(origin.kind == BackgroundActivityOrigin.Kind.COMMAND)
        CommandScriptTokens.requireValidHash(tokenHash)
        // Registration is not renewal. Even an existing binding cannot mint a new credential
        // after its creating RPC completes or expires; ordinary task recovery never enters here.
        c.prepareStatement("""
            SELECT worker_id, actor_user_id, project_id, dispatched_at, cancel_requested_at, completed_at, execution_deadline
            FROM worker_requests WHERE id=? FOR UPDATE
        """.trimIndent()).use { s ->
            s.setString(1, origin.workerRequestId)
            s.executeQuery().use { r ->
                val current = maxOf(at, Clock.System.now())
                require(r.next() && r.getString("worker_id") == origin.workerId.value &&
                    r.getString("actor_user_id") == origin.actorUserId.value && r.getString("project_id") == projectId.value &&
                    r.getTimestamp("dispatched_at") != null && r.getTimestamp("cancel_requested_at") == null &&
                    r.getTimestamp("completed_at") == null && r.getTimestamp("execution_deadline")?.toInstant()?.isAfter(current.java()) == true) {
                    "Command SDK registration requires its pending launch request"
                }
            }
        }
        val issued = c.prepareStatement("SELECT record_json::text,script_grant_issued FROM background_activity_origins WHERE kind='COMMAND' AND activity_id=? FOR UPDATE").use { s ->
            s.setString(1, origin.activityId)
            s.executeQuery().use { r ->
                require(r.next() && json.decodeFromString<BackgroundActivityOrigin>(r.getString(1)) == origin) {
                    "Command SDK registration requires trusted launch provenance"
                }
                r.getBoolean(2)
            }
        }
        val existing = c.prepareStatement("SELECT * FROM command_script_grants WHERE command_id=? FOR UPDATE").use { s ->
            s.setString(1, origin.activityId)
            s.executeQuery().use { r -> if (r.next()) r.grant() else null }
        }
        if (existing != null) {
            require(issued && existing.origin == origin && existing.projectId == projectId &&
                existing.tokenHash == tokenHash && existing.revokedAt == null) {
                "Command SDK grant cannot be replaced or revived"
            }
            return@transaction existing
        }
        require(!issued) { "Command SDK grant was already retired" }
        c.prepareStatement("""
            INSERT INTO command_script_grants(command_id,conversation_id,actor_user_id,project_id,token_hash,origin_json,created_at,checked_at)
            VALUES (?,?,?,?,?,?::jsonb,?,?)
        """.trimIndent()).use { s ->
            listOf(origin.activityId, origin.conversationId.value, origin.actorUserId.value, projectId.value,
                tokenHash, json.encodeToString(origin)).forEachIndexed { i, value -> s.setString(i + 1, value) }
            s.setTimestamp(7, at.sql()); s.setTimestamp(8, at.sql()); s.executeUpdate()
        }
        c.prepareStatement("UPDATE background_activity_origins SET script_grant_issued=TRUE WHERE kind='COMMAND' AND activity_id=? AND script_grant_issued=FALSE").use { s ->
            s.setString(1, origin.activityId)
            check(s.executeUpdate() == 1) { "Command SDK issuance marker changed during registration" }
        }
        c.prepareStatement("SELECT * FROM command_script_grants WHERE command_id=?").use { s ->
            s.setString(1, origin.activityId)
            s.executeQuery().use { r -> check(r.next()); r.grant() }
        }
    }

    override suspend fun findByHash(tokenHash: String): CommandScriptGrant? = connection { c ->
        c.prepareStatement("SELECT * FROM command_script_grants WHERE token_hash=?").use { s ->
            s.setString(1, tokenHash)
            s.executeQuery().use { r -> if (r.next()) r.grant() else null }
        }
    }

    override suspend fun revoke(conversationId: Conversation.Id, taskId: CommandTask.Id, at: Instant): Unit = connection { c ->
        c.prepareStatement("UPDATE command_script_grants SET revoked_at=COALESCE(revoked_at,?) WHERE command_id=? AND conversation_id=?").use { s ->
            s.setTimestamp(1, at.sql()); s.setString(2, taskId.value); s.setString(3, conversationId.value); s.executeUpdate()
        }
        Unit
    }

    override suspend fun maintenanceCandidates(checkedBefore: Instant, limit: Int): List<CommandScriptGrant> = connection { c ->
        require(limit in 1..256)
        c.prepareStatement("SELECT * FROM command_script_grants WHERE revoked_at IS NULL AND checked_at<=? ORDER BY checked_at,command_id LIMIT ?").use { s ->
            s.setTimestamp(1, checkedBefore.sql()); s.setInt(2, limit)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.grant()) } }
        }
    }

    override suspend fun markChecked(taskId: CommandTask.Id, at: Instant, reminderDay: Long): Unit = connection { c ->
        require(reminderDay >= 0)
        c.prepareStatement("UPDATE command_script_grants SET checked_at=GREATEST(checked_at,?), last_reminder_day=GREATEST(last_reminder_day,?) WHERE command_id=? AND revoked_at IS NULL").use { s ->
            s.setTimestamp(1, at.sql()); s.setLong(2, reminderDay); s.setString(3, taskId.value); s.executeUpdate()
        }
        Unit
    }

    override suspend fun deleteRevokedBefore(before: Instant, limit: Int): Int = connection { c ->
        require(limit in 1..256)
        c.prepareStatement("""
            DELETE FROM command_script_grants WHERE command_id IN
            (SELECT command_id FROM command_script_grants WHERE revoked_at<? ORDER BY revoked_at,command_id LIMIT ?)
        """.trimIndent()).use { s -> s.setTimestamp(1, before.sql()); s.setInt(2, limit); s.executeUpdate() }
    }

    private fun ResultSet.grant(): CommandScriptGrant {
        val origin = json.decodeFromString<BackgroundActivityOrigin>(getString("origin_json"))
        check(origin.kind == BackgroundActivityOrigin.Kind.COMMAND && origin.activityId == getString("command_id") &&
            origin.conversationId.value == getString("conversation_id") && origin.actorUserId.value == getString("actor_user_id")) {
            "Stored command SDK grant identity is inconsistent"
        }
        return CommandScriptGrant(origin, Project.Id(getString("project_id")), getString("token_hash"),
            getTimestamp("created_at").kotlin(), getTimestamp("checked_at").kotlin(), getLong("last_reminder_day"),
            getTimestamp("revoked_at")?.kotlin())
    }

    private suspend fun <T> connection(block: (Connection) -> T): T = withContext(Dispatchers.IO) { dataSource.connection.use(block) }
    private suspend fun <T> transaction(block: (Connection) -> T): T = connection { c ->
        c.autoCommit = false
        try { block(c).also { c.commit() } } catch (error: Throwable) { c.rollback(); throw error }
    }
    private fun Instant.java(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())
    private fun Instant.sql(): Timestamp = Timestamp.from(java())
    private fun Timestamp.kotlin(): Instant = toInstant().let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
}
