package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.UserNotificationRepository
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Instant

@Service
internal class PostgresUserNotificationRepository(private val dataSource: DataSource) : UserNotificationRepository {
    override suspend fun publish(userId: User.Id, draft: UserNotificationDraft, at: Instant): UserNotificationRepository.PublishResult = transaction { c ->
        val id = UserNotification.Id(uuid7())
        val inserted = c.prepareStatement("""
            INSERT INTO user_notifications(id,user_id,source_key,title,message,level,conversation_id,created_at)
            VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (user_id,source_key) DO NOTHING RETURNING *
        """.trimIndent()).use { s ->
            s.setString(1,id.value); s.setString(2,userId.value); s.setString(3,draft.sourceKey)
            s.setString(4,draft.title); s.setString(5,draft.message); s.setString(6,draft.level.name)
            s.setString(7,draft.conversationId?.value); s.setTimestamp(8,at.sql())
            s.executeQuery().use { r -> if(r.next()) r.notification() else null }
        }
        if (inserted != null) UserNotificationRepository.PublishResult(inserted, true)
        else {
            checkNotNull(draft.sourceKey) { "Notification id conflict" }
            val existing = c.prepareStatement("SELECT * FROM user_notifications WHERE user_id=? AND source_key=?").use { s ->
                s.setString(1,userId.value); s.setString(2,draft.sourceKey)
                s.executeQuery().use { r -> check(r.next()); r.notification() }
            }
            check(existing.title == draft.title && existing.message == draft.message && existing.level == draft.level && existing.conversationId == draft.conversationId) {
                "Notification source key was reused with different content"
            }
            UserNotificationRepository.PublishResult(existing, false)
        }
    }

    override suspend fun summary(userId: User.Id): UserNotificationSummary = withConnection { c ->
        // One statement keeps the badge and latest item in the same database snapshot.
        c.prepareStatement("""
            SELECT n.*, counts.unread_count FROM
              (SELECT count(*) AS unread_count FROM user_notifications WHERE user_id=? AND deleted_at IS NULL AND read_at IS NULL) counts
            LEFT JOIN LATERAL
              (SELECT * FROM user_notifications WHERE user_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 1) n ON true
        """.trimIndent()).use { s ->
            s.setString(1,userId.value); s.setString(2,userId.value)
            s.executeQuery().use { r ->
                check(r.next())
                UserNotificationSummary(r.getLong("unread_count"), if(r.getString("id") == null) null else r.notification())
            }
        }
    }

    override suspend fun list(userId: User.Id, before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean): UserNotificationPage = withConnection { c ->
        require(limit in 1..100) { "Notification page size must be between 1 and 100" }
        val cursor = if (before == null) "" else " AND (created_at,id) < (?,?)"
        val unread = if (unreadOnly) " AND read_at IS NULL" else ""
        val items = c.prepareStatement("SELECT * FROM user_notifications WHERE user_id=? AND deleted_at IS NULL$unread$cursor ORDER BY created_at DESC,id DESC LIMIT ?").use { s ->
            s.setString(1,userId.value)
            var parameter=2
            if(before != null) { s.setTimestamp(parameter++,before.createdAt.sql()); s.setString(parameter++,before.id.value) }
            s.setInt(parameter,limit+1)
            s.executeQuery().use { r -> buildList { while(r.next()) add(r.notification()) } }
        }
        val page=items.take(limit)
        UserNotificationPage(page, if(items.size > limit) page.last().let { UserNotificationCursor(it.createdAt,it.id) } else null)
    }

    override suspend fun setRead(userId: User.Id, id: UserNotification.Id, read: Boolean, at: Instant): UserNotification = withConnection { c ->
        c.prepareStatement("UPDATE user_notifications SET read_at=CASE WHEN ? THEN COALESCE(read_at,?) ELSE NULL END WHERE user_id=? AND id=? AND deleted_at IS NULL RETURNING *").use { s ->
            s.setBoolean(1,read); s.setTimestamp(2,at.sql()); s.setString(3,userId.value); s.setString(4,id.value)
            s.executeQuery().use { r -> if(!r.next()) throw UserNotificationNotFoundException(); r.notification() }
        }
    }

    override suspend fun markAllRead(userId: User.Id, at: Instant): Int = withConnection { c ->
        c.prepareStatement("UPDATE user_notifications SET read_at=? WHERE user_id=? AND deleted_at IS NULL AND read_at IS NULL").use { s ->
            s.setTimestamp(1,at.sql()); s.setString(2,userId.value); s.executeUpdate()
        }
    }

    override suspend fun delete(userId: User.Id, id: UserNotification.Id): Boolean = withConnection { c ->
        // Keep the source key so retrying an acknowledged publication cannot resurrect a dismissed item.
        c.prepareStatement("UPDATE user_notifications SET deleted_at=clock_timestamp() WHERE user_id=? AND id=? AND deleted_at IS NULL").use { s ->
            s.setString(1,userId.value); s.setString(2,id.value); s.executeUpdate() == 1
        }
    }

    private suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T = withConnection { c ->
        c.autoCommit=false
        try { block(c).also { c.commit() } }
        catch(error: Throwable) { c.rollback(); throw error }
    }

    private fun ResultSet.notification() = UserNotification(
        id=UserNotification.Id(getString("id")), userId=User.Id(getString("user_id")),
        title=getString("title"), message=getString("message"), level=UserNotification.Level.valueOf(getString("level")),
        conversationId=getString("conversation_id")?.let(Conversation::Id),
        createdAt=getTimestamp("created_at").instant(), readAt=getTimestamp("read_at")?.instant(),
    )

    private fun Instant.sql() = Timestamp.from(java.time.Instant.ofEpochSecond(epochSeconds,nanosecondsOfSecond.toLong()))
    private fun Timestamp.instant() = toInstant().let { Instant.fromEpochSeconds(it.epochSecond,it.nano.toLong()) }
}
