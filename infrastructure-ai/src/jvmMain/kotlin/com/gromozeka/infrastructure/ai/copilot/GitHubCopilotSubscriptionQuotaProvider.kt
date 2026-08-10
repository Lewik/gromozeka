package com.gromozeka.infrastructure.ai.copilot

import com.github.copilot.generated.rpc.AccountGetQuotaParams
import com.github.copilot.generated.rpc.AccountQuotaSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaWindow
import com.gromozeka.domain.repository.AiUserCredentialRepository
import com.gromozeka.domain.service.DirectAiSubscriptionQuotaProvider
import kotlin.time.Clock
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
internal class GitHubCopilotSubscriptionQuotaProvider(
    private val clientPool: GitHubCopilotClientPool,
    credentialRepositories: List<AiUserCredentialRepository>,
) : DirectAiSubscriptionQuotaProvider {
    private val credentialRepository = credentialRepositories.singleOrNull()

    init {
        require(credentialRepositories.size <= 1) {
            "Multiple GitHub Copilot credential repositories are configured"
        }
    }

    override fun supports(request: AiSubscriptionQuotaRequest): Boolean =
        request.connection.kind == AiConnection.Kind.GITHUB_COPILOT

    override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot {
        val connection = request.connection as? AiConnection.GitHubCopilot
            ?: error("GitHub Copilot quota requires a GitHub Copilot connection")
        val handle = clientPool.acquire(connection)
        val result = when (connection.authMode) {
            AiConnection.GitHubCopilotAuthMode.SERVER_CLI -> handle.client.rpc.account.getQuota()
            AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN -> {
                val repository = requireNotNull(credentialRepository) {
                    "GitHub Copilot per-user authentication is unavailable on this execution target"
                }
                val userId = request.userId?.let(User::Id)
                    ?: error("GitHub Copilot per-user quota requires a user id")
                val token = repository.find(userId, connection.id)?.secret
                    ?: error("GitHub Copilot is not authorized for the current user")
                handle.client.rpc.account.getQuota(AccountGetQuotaParams(token))
            }
        }.awaitCancellable()

        return GitHubCopilotQuotaMapper.map(
            snapshots = result.quotaSnapshots(),
            connectionId = connection.id,
        )
    }
}

internal object GitHubCopilotQuotaMapper {
    fun map(
        snapshots: Map<String, AccountQuotaSnapshot>?,
        connectionId: AiConnection.Id,
        observedAt: Instant = Clock.System.now(),
    ): AiSubscriptionQuotaSnapshot {
        val availableSnapshots = snapshots.orEmpty()
        val finiteSnapshots = availableSnapshots.filterValues { it.isUnlimitedEntitlement() != true }
        return AiSubscriptionQuotaSnapshot(
            connectionId = connectionId,
            observedAt = observedAt,
            windows = finiteSnapshots.map { (id, snapshot) -> snapshot.toWindow(id) },
            unlimited = finiteSnapshots.isEmpty() && availableSnapshots.isNotEmpty(),
        )
    }

    private fun AccountQuotaSnapshot.toWindow(id: String): AiSubscriptionQuotaWindow {
        val reset = resetDate() ?: error("GitHub Copilot quota $id did not report a reset date")
        val entitlement = entitlementRequests()
        val used = usedRequests()
        val usedPercent = if (entitlement != null && entitlement > 0L && used != null) {
            used.toDouble() * 100.0 / entitlement.toDouble()
        } else {
            100.0 - requireNotNull(remainingPercentage()) {
                "GitHub Copilot quota $id did not report usage"
            }
        }.coerceIn(0.0, 100.0)
        return AiSubscriptionQuotaWindow(
            id = id,
            displayName = id.replace('_', ' '),
            usedPercent = usedPercent,
            startedAt = reset.minusMonths(1).toInstant().toKotlinInstant(),
            resetsAt = reset.toInstant().toKotlinInstant(),
        )
    }

    private fun java.time.Instant.toKotlinInstant(): Instant =
        Instant.fromEpochMilliseconds(toEpochMilli())
}
