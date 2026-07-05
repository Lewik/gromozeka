package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ai.ClaudeCodeSessionState

interface ClaudeCodeSessionStateRepository {
    suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState?

    suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState

    suspend fun delete(key: ClaudeCodeSessionState.Key)
}
