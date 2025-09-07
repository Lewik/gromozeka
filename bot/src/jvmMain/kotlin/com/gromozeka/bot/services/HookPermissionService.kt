package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeHookPayload
import com.gromozeka.bot.model.HookDecision
import klog.KLoggers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlin.time.Clock
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class HookPermissionService {
    private val log = KLoggers.logger(this)

    // === ACTOR CHANNELS ===
    private val commandChannel = Channel<Command>(capacity = Channel.UNLIMITED)
    
    // === ACTOR STATE ===
    private var actorState = ActorState()
    private var actorJob: Job? = null
    private var actorScope: CoroutineScope? = null
    
    // === OUTGOING FLOWS FOR UI ===
    private val _pendingRequests = MutableStateFlow<Map<String, ClaudeHookPayload>>(emptyMap())
    val pendingRequests: StateFlow<Map<String, ClaudeHookPayload>> = _pendingRequests.asStateFlow()
    
    private val _resolvedRequests = MutableSharedFlow<Pair<String, HookDecision>>()
    val resolvedRequests: SharedFlow<Pair<String, HookDecision>> = _resolvedRequests.asSharedFlow()

    // === ACTOR COMMAND DEFINITIONS ===
    
    /**
     * Commands for the HookPermission actor
     */
    sealed class Command {
        data class ProcessRequest(
            val hookPayload: ClaudeHookPayload,
            val responseDeferred: CompletableDeferred<HookDecision>
        ) : Command()
        
        data class ResolveRequest(
            val sessionId: String,
            val decision: HookDecision
        ) : Command()
        
        data class TimeoutRequest(val sessionId: String) : Command()
        
        data object CancelAllRequests : Command()
    }
    
    /**
     * Actor state containing pending requests
     */
    private data class ActorState(
        val pendingRequests: Map<String, PendingRequest> = emptyMap()
    )
    
    /**
     * Internal pending request state
     */
    private data class PendingRequest(
        val hookPayload: ClaudeHookPayload,
        val responseDeferred: CompletableDeferred<HookDecision>,
        val createdAt: Instant,
        val timeoutJob: Job?
    )

    // === ACTOR LOOP ===
    
    /**
     * Main actor loop processing commands sequentially
     */
    private suspend fun actorLoop() {
        log.debug("Starting HookPermission actor loop")
        
        while (true) {
            select<Unit> {
                commandChannel.onReceive { command ->
                    log.debug("Processing command: $command")
                    handleCommand(command)
                }
            }
        }
    }
    
    /**
     * Handle actor commands
     */
    private suspend fun handleCommand(command: Command) {
        when (command) {
            is Command.ProcessRequest -> handleProcessRequest(command)
            is Command.ResolveRequest -> handleResolveRequest(command)
            is Command.TimeoutRequest -> handleTimeoutRequest(command)
            is Command.CancelAllRequests -> handleCancelAllRequests()
        }
    }
    
    /**
     * Handle incoming permission request from HTTP
     */
    private suspend fun handleProcessRequest(cmd: Command.ProcessRequest) {
        val sessionId = cmd.hookPayload.session_id
        val timeoutMs = 30000L
        
        log.info("Processing hook permission for tool ${cmd.hookPayload.tool_name} (session: $sessionId)")
        
        // Create timeout job
        val timeoutJob = actorScope?.launch {
            delay(timeoutMs)
            commandChannel.send(Command.TimeoutRequest(sessionId))
        }
        
        val pendingRequest = PendingRequest(
            hookPayload = cmd.hookPayload,
            responseDeferred = cmd.responseDeferred,
            createdAt = Clock.System.now(),
            timeoutJob = timeoutJob
        )
        
        // Update actor state
        actorState = actorState.copy(
            pendingRequests = actorState.pendingRequests + (sessionId to pendingRequest)
        )
        
        // Update UI flow - dialog will be shown automatically via reactive subscription
        _pendingRequests.value = actorState.pendingRequests.mapValues { it.value.hookPayload }
        
        log.info("Hook permission request added to pending queue for tool: ${cmd.hookPayload.tool_name}")
    }
    
    /**
     * Handle user decision from UI
     */
    private suspend fun handleResolveRequest(cmd: Command.ResolveRequest) {
        val pendingRequest = actorState.pendingRequests[cmd.sessionId]
        
        if (pendingRequest != null) {
            log.info("Resolved hook permission for session ${cmd.sessionId}: allow=${cmd.decision.allow}")
            
            // Cancel timeout job
            pendingRequest.timeoutJob?.cancel()
            
            // Complete the HTTP request
            pendingRequest.responseDeferred.complete(cmd.decision)
            
            // Remove from state
            actorState = actorState.copy(
                pendingRequests = actorState.pendingRequests - cmd.sessionId
            )
            
            // Update UI flow
            _pendingRequests.value = actorState.pendingRequests.mapValues { it.value.hookPayload }
            _resolvedRequests.emit(cmd.sessionId to cmd.decision)
            
        } else {
            log.warn("Attempted to resolve unknown or already completed request: ${cmd.sessionId}")
        }
    }
    
    /**
     * Handle timeout for a request
     */
    private suspend fun handleTimeoutRequest(cmd: Command.TimeoutRequest) {
        val pendingRequest = actorState.pendingRequests[cmd.sessionId]
        
        if (pendingRequest != null) {
            log.warn("Hook permission request ${cmd.sessionId} timed out after 30s")
            
            val timeoutDecision = HookDecision(
                allow = false,
                reason = "Request timed out after 30 seconds"
            )
            
            // Complete the HTTP request
            pendingRequest.responseDeferred.complete(timeoutDecision)
            
            // Remove from state
            actorState = actorState.copy(
                pendingRequests = actorState.pendingRequests - cmd.sessionId
            )
            
            // Update UI flow
            _pendingRequests.value = actorState.pendingRequests.mapValues { it.value.hookPayload }
            _resolvedRequests.emit(cmd.sessionId to timeoutDecision)
        }
    }
    
    /**
     * Handle cancel all requests (shutdown)
     */
    private suspend fun handleCancelAllRequests() {
        val count = actorState.pendingRequests.size
        
        if (count > 0) {
            val shutdownDecision = HookDecision(
                allow = false,
                reason = "Service shutting down"
            )
            
            actorState.pendingRequests.forEach { (sessionId, pendingRequest) ->
                // Cancel timeout job
                pendingRequest.timeoutJob?.cancel()
                
                // Complete HTTP request
                if (!pendingRequest.responseDeferred.isCompleted) {
                    pendingRequest.responseDeferred.complete(shutdownDecision)
                }
            }
            
            // Clear state
            actorState = actorState.copy(pendingRequests = emptyMap())
            _pendingRequests.value = emptyMap()
            
            log.info("Cancelled $count pending hook permission requests due to service shutdown")
        }
    }
    
    
    // === PUBLIC API (Actor Command Proxies) ===
    
    /**
     * Initialize the actor system
     */
    fun initializeActor(scope: CoroutineScope) {
        if (actorJob == null) {
            actorScope = scope
            actorJob = scope.launch { actorLoop() }
            log.debug("HookPermission actor initialized")
        }
    }
    
    /**
     * Send command directly to actor - this is the primary Actor API
     */
    suspend fun sendCommand(command: Command) {
        commandChannel.send(command)
    }
}