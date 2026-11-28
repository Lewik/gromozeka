package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserNotification
import com.gromozeka.domain.model.UserNotificationDraft
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.UserNotificationPublisher
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

class CommandJournalBestEffortWriterTest {
    @Test
    fun `writer exists only when Command Journal is enabled`() {
        val runner = ApplicationContextRunner()
            .withUserConfiguration(CommandJournalBestEffortWriter::class.java)
            .withBean(UserNotificationPublisher::class.java, {
                UserNotificationPublisher { _, _ -> error("unused") }
            })

        runner.run { context ->
            assertFalse(context.containsBean("commandJournalBestEffortWriter"))
        }
        runner.withPropertyValues("gromozeka.command-journal.enabled=true").run { context ->
            context.getBean(CommandJournalBestEffortWriter::class.java)
        }
    }

    @Test
    fun `storage failure preserves the operation and publishes a bounded notification`() = runBlocking {
        val published = mutableListOf<Pair<User.Id, UserNotificationDraft>>()
        val writer = CommandJournalBestEffortWriter(
            UserNotificationPublisher { user, draft ->
                published += user to draft
                UserNotification(
                    id = UserNotification.Id("notification"),
                    userId = user,
                    title = draft.title,
                    message = draft.message,
                    level = draft.level,
                    createdAt = Instant.fromEpochSeconds(1),
                    conversationId = draft.conversationId,
                )
            },
        )
        val failure = CommandJournal.StorageException(SQLException("database unavailable"))
        val result = writer.write(
            operation = "record command lifecycle",
            context = {
                CommandJournalBestEffortWriter.Context(
                    actorUserId = User.Id("user"),
                    conversationId = Conversation.Id("conversation"),
                    correlation = "command-task:task:COMPLETED",
                )
            },
        ) { throw failure }

        assertNull(result)
        val (user, draft) = published.single()
        assertEquals(User.Id("user"), user)
        assertEquals(Conversation.Id("conversation"), draft.conversationId)
        assertEquals(UserNotification.Level.WARNING, draft.level)
        assertEquals("Command Journal observation was not recorded", draft.title)
        assertEquals(true, draft.message.contains("No special action is required"))
        assertEquals(true, draft.sourceKey?.startsWith("command-journal:") == true)
    }

    @Test
    fun `notification failure does not change the original operation`() = runBlocking {
        var publicationAttempts = 0
        val writer = CommandJournalBestEffortWriter(
            UserNotificationPublisher { _, _ ->
                publicationAttempts++
                throw IllegalStateException("notification unavailable")
            },
        )

        val result = writer.write(
            operation = "record command lifecycle",
            context = {
                CommandJournalBestEffortWriter.Context(
                    actorUserId = User.Id("user"),
                    conversationId = Conversation.Id("conversation"),
                    correlation = "task",
                )
            },
        ) { throw CommandJournal.StorageException(SQLException("database unavailable")) }

        assertNull(result)
        assertEquals(1, publicationAttempts)
    }

    @Test
    fun `writer does not hide a Journal invariant violation`() = runBlocking {
        val writer = CommandJournalBestEffortWriter(
            UserNotificationPublisher { _, _ -> error("must not publish") },
        )
        val failure = IllegalStateException("identity changed")

        val thrown = assertFailsWith<IllegalStateException> {
            writer.write(
                operation = "record command admission",
                context = { CommandJournalBestEffortWriter.Context(null, null, "call") },
            ) { throw failure }
        }

        assertSame(failure, thrown)
    }
}
