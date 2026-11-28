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

class CommandJournalWritePolicyTest {
    @Test
    fun `configuration defaults to best effort and accepts required`() {
        val runner = ApplicationContextRunner()
            .withUserConfiguration(CommandJournalWritePolicy::class.java)
            .withBean(UserNotificationPublisher::class.java, {
                UserNotificationPublisher { _, _ -> error("unused") }
            })
        runner.run { context ->
            assertFalse(context.containsBean("commandJournalWritePolicy"))
        }
        val base = runner.withPropertyValues("gromozeka.command-journal.enabled=true")
        base.run { context ->
            assertEquals(CommandJournal.FailureMode.BEST_EFFORT, context.getBean(CommandJournalWritePolicy::class.java).mode)
        }
        base.withPropertyValues("gromozeka.command-journal.failure-mode=REQUIRED").run { context ->
            assertEquals(CommandJournal.FailureMode.REQUIRED, context.getBean(CommandJournalWritePolicy::class.java).mode)
        }
    }

    @Test
    fun `best effort preserves the operation and publishes a bounded notification`() = runBlocking {
        val published = mutableListOf<Pair<User.Id, UserNotificationDraft>>()
        val policy = CommandJournalWritePolicy(
            CommandJournal.FailureMode.BEST_EFFORT,
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
        val result = policy.write(
            operation = "record command lifecycle",
            context = {
                CommandJournalWritePolicy.Context(
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
    fun `best effort does not let notification failure change the original operation`() = runBlocking {
        var publicationAttempts = 0
        val policy = CommandJournalWritePolicy(
            CommandJournal.FailureMode.BEST_EFFORT,
            UserNotificationPublisher { _, _ ->
                publicationAttempts++
                throw IllegalStateException("notification unavailable")
            },
        )

        val result = policy.write(
            operation = "record command lifecycle",
            context = {
                CommandJournalWritePolicy.Context(
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
    fun `required propagates the original storage failure without publishing`() = runBlocking {
        var publications = 0
        val policy = CommandJournalWritePolicy(
            CommandJournal.FailureMode.REQUIRED,
            UserNotificationPublisher { _, _ ->
                publications++
                error("must not publish")
            },
        )
        val failure = CommandJournal.StorageException(SQLException("database unavailable"))

        val thrown = assertFailsWith<CommandJournal.StorageException> {
            policy.write(
                operation = "record command admission",
                context = {
                    CommandJournalWritePolicy.Context(User.Id("user"), Conversation.Id("conversation"), "call")
                },
            ) { throw failure }
        }

        assertSame(failure, thrown)
        assertEquals(0, publications)
    }

    @Test
    fun `best effort does not hide a Journal invariant violation`() = runBlocking {
        val policy = CommandJournalWritePolicy(
            CommandJournal.FailureMode.BEST_EFFORT,
            UserNotificationPublisher { _, _ -> error("must not publish") },
        )
        val failure = IllegalStateException("identity changed")

        val thrown = assertFailsWith<IllegalStateException> {
            policy.write(
                operation = "record command admission",
                context = { CommandJournalWritePolicy.Context(null, null, "call") },
            ) { throw failure }
        }

        assertSame(failure, thrown)
    }
}
