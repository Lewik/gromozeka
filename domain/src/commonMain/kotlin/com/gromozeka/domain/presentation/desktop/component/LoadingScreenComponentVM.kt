package com.gromozeka.domain.presentation.desktop.component

import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for application loading screen component.
 *
 * ## UI Layout (Loading Screen)
 * ```
 * ┌─────────────────────────────────────────────────┐
 * │                                                 │
 * │                                                 │
 * │              ┌─────────────────┐                │
 * │              │   GROMOZEKA     │                │
 * │              │   🦑 Multi-AI   │                │
 * │              └─────────────────┘                │
 * │                                                 │
 * │              Initializing...                    │
 * │              ⏳                                  │
 * │                                                 │
 * └─────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────┐
 * │              Starting MCP Servers               │
 * │                                                 │
 * │              Loading: filesystem (2/5)          │
 * │              ████████░░░░░░░░░░░░  40%          │
 * │                                                 │
 * └─────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────┐
 * │              ✓ Ready                            │
 * │              (auto-dismiss after 500ms)         │
 * └─────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────┐
 * │              ✗ Error                            │
 * │                                                 │
 * │              Failed to start MCP server:        │
 * │              filesystem - connection timeout    │
 * │                                                 │
 * │              [Retry] [Skip] [Quit]              │
 * └─────────────────────────────────────────────────┘
 * ```
 *
 * ## Behavior
 *
 * ### Initialization Flow
 * 1. Screen appears immediately on app launch
 * 2. [initialize] called automatically
 * 3. State: [LoadingState.Initializing]
 * 4. Start MCP server initialization
 * 5. For each MCP server:
 *    - State: [LoadingState.LoadingMCP] (serverName, current, total)
 *    - Progress bar updates: current/total
 *    - Server name displayed
 * 6. All servers started successfully:
 *    - State: [LoadingState.Complete]
 *    - Auto-dismiss after 500ms
 *    - Main app UI appears
 * 7. Error during initialization:
 *    - State: [LoadingState.Error] (message)
 *    - Display error message
 *    - Show retry/skip/quit buttons
 *
 * ### State Machine
 * ```
 * Initializing → LoadingMCP(server1, 1, 5) → LoadingMCP(server2, 2, 5) → ... 
 *             → LoadingMCP(server5, 5, 5) → Complete → (dismiss)
 *             ↘ Error (on failure at any step)
 * ```
 *
 * ### MCP Server Loading
 * MCP (Model Context Protocol) servers provide tools and context to AI:
 * - **filesystem** - file read/write operations
 * - **git** - version control operations
 * - **brave-search** - web search capability
 * - **memory** - knowledge graph access
 * - Custom project-specific servers
 *
 * Each server started sequentially, progress tracked via callback.
 *
 * ## Error Handling
 * - Network timeout → display error, allow retry
 * - Invalid config → display error, suggest fix
 * - Server crash → display error, allow skip
 * - User can proceed with partial MCP setup (skip failed servers)
 *
 * ## Performance
 * - Typical load time: 2-5 seconds
 * - Parallel server start not supported (sequential for stability)
 * - Progress updates every 100ms minimum
 *
 * @property loadingState Current loading state (state machine)
 */
interface LoadingScreenComponentVM {
    // State (survives recomposition)
    val loadingState: StateFlow<LoadingState>
    
    // Actions
    /**
     * Initialize application and MCP servers.
     * This is a TRANSACTIONAL operation - starts all MCP servers atomically.
     *
     * Initialization steps:
     * 1. Set state to [LoadingState.Initializing]
     * 2. Start each MCP server sequentially
     * 3. Update state to [LoadingState.LoadingMCP] for each server
     * 4. On success → [LoadingState.Complete]
     * 5. On error → [LoadingState.Error]
     *
     * This method should be called once on app startup.
     * Safe to call multiple times (idempotent).
     */
    suspend fun initialize()
    
    /**
     * Loading state machine.
     * Represents current stage of application initialization.
     */
    sealed class LoadingState {
        /**
         * Initial state before MCP server loading starts.
         * Shows generic "Initializing..." spinner.
         */
        data object Initializing : LoadingState()
        
        /**
         * Loading specific MCP server.
         * Shows progress bar with server name and completion percentage.
         *
         * @property serverName Name of MCP server being loaded (e.g., "filesystem")
         * @property current Index of current server (1-based)
         * @property total Total number of servers to load
         */
        data class LoadingMCP(
            val serverName: String,
            val current: Int,
            val total: Int
        ) : LoadingState()
        
        /**
         * All initialization complete successfully.
         * Shows success checkmark, auto-dismisses after 500ms.
         */
        data object Complete : LoadingState()
        
        /**
         * Initialization failed.
         * Shows error message and retry/skip/quit options.
         *
         * @property message Error description (user-friendly)
         */
        data class Error(val message: String) : LoadingState()
    }
}
