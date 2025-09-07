package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeHookPayload
import com.gromozeka.bot.model.HookDecision
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import klog.KLoggers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class HookPermissionService(
    private val applicationContext: ApplicationContext,
) {
    private val log = KLoggers.logger(this)

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<HookDecision>>()

    /**
     * Handle incoming Claude Code CLI hook permission request
     * This method blocks until user makes a decision or timeout occurs
     */
    suspend fun handleHookPermission(hookPayload: ClaudeHookPayload): HookDecision {
        val requestId = hookPayload.session_id // Use session_id as request identifier
        val timeoutMs = 30000L // Fixed 30 second timeout

        log.info("Processing hook permission for tool ${hookPayload.tool_name} (session: $requestId)")

        val decisionFuture = CompletableDeferred<HookDecision>()
        pendingRequests[requestId] = decisionFuture

        try {
            showPermissionDialog(hookPayload)
            val decision = withTimeout(timeoutMs) {
                decisionFuture.await()
            }

            log.info("Hook permission for $requestId resolved: allow=${decision.allow}")
            return decision

        } catch (e: TimeoutCancellationException) {
            log.warn("Hook permission request $requestId timed out after 30s")
            return HookDecision(
                allow = false,
                reason = "Request timed out after 30 seconds"
            )
        } catch (e: Exception) {
            log.error(e, "Error processing hook permission request $requestId")
            return HookDecision(
                allow = false,
                reason = "Internal error: ${e.message}"
            )
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    /**
     * Called by UI when user makes a decision
     */
    fun resolveHookPermission(sessionId: String, decision: HookDecision) {
        val future = pendingRequests[sessionId]
        if (future != null && !future.isCompleted) {
            future.complete(decision)
            log.info("Resolved hook permission for session $sessionId: allow=${decision.allow}")
        } else {
            log.warn("Attempted to resolve unknown or already completed request: $sessionId")
        }
    }

    /**
     * Show permission dialog in UI
     */
    private suspend fun showPermissionDialog(hookPayload: ClaudeHookPayload) {
        try {
            val appViewModel = applicationContext.getBean(AppViewModel::class.java)

            // UI operations via StateFlow are thread-safe
            appViewModel.showClaudeHookPermissionDialog(hookPayload)

            log.info("Permission dialog shown for Claude hook: ${hookPayload.tool_name}")

        } catch (e: Exception) {
            log.error(e, "Failed to show permission dialog for Claude hook")

            // Auto-deny if we can't show UI
            resolveHookPermission(
                hookPayload.session_id,
                HookDecision(
                    allow = false,
                    reason = "Failed to show permission dialog: ${e.message}"
                )
            )
        }
    }

    /**
     * Get list of currently pending permission requests
     */
    fun getPendingRequests(): Map<String, Boolean> {
        return pendingRequests.mapValues { !it.value.isCompleted }
    }

    /**
     * Cancel all pending requests (e.g., on shutdown)
     */
    fun cancelAllPendingRequests() {
        val count = pendingRequests.size
        pendingRequests.forEach { (sessionId, future) ->
            if (!future.isCompleted) {
                future.complete(
                    HookDecision(
                        allow = false,
                        reason = "Service shutting down"
                    )
                )
            }
        }
        pendingRequests.clear()

        if (count > 0) {
            log.info("Cancelled $count pending hook permission requests due to service shutdown")
        }
    }
}