package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserNotification
import com.gromozeka.domain.model.UserNotificationDraft
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.UserNotificationPublisher
import com.gromozeka.shared.utils.sha256
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** Immediate write policy only. It never retries, replays, or reconstructs a missing Journal observation. */
@Service
@ConditionalOnProperty(name = ["gromozeka.command-journal.enabled"], havingValue = "true")
class CommandJournalWritePolicy(
    @Value("\${gromozeka.command-journal.failure-mode:BEST_EFFORT}")
    val mode: CommandJournal.FailureMode,
    private val notifications: UserNotificationPublisher,
) {
    data class Context(
        val actorUserId: User.Id?,
        val conversationId: Conversation.Id?,
        val correlation: String,
    )

    private val log = KLoggers.logger(this)

    suspend fun <T> write(
        operation: String,
        context: () -> Context,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CommandJournal.StorageException) {
        if (mode == CommandJournal.FailureMode.REQUIRED) throw failure
        val current = context()
        log.error(failure) { "Command Journal could not $operation; execution continues in BEST_EFFORT mode" }
        current.actorUserId?.let { actor ->
            try {
                notifications.publish(
                    actor,
                    UserNotificationDraft(
                        title = "Command Journal observation was not recorded",
                        message = "The original operation continues normally, but Command Journal could not $operation. " +
                            "Some audit information may be missing. No special action is required.",
                        level = UserNotification.Level.WARNING,
                        conversationId = current.conversationId,
                        sourceKey = "command-journal:${"$operation:${current.correlation}".sha256()}",
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (notificationFailure: Exception) {
                failure.addSuppressed(notificationFailure)
                log.error(notificationFailure) { "Command Journal write-failure notification could not be published" }
            }
        }
        null
    }
}
