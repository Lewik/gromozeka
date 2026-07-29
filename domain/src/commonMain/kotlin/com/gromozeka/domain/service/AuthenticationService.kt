package com.gromozeka.domain.service

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.IssuedUserSession
import com.gromozeka.domain.model.User

interface PasswordHasher {
    fun hash(password: CharArray): String
    fun verify(password: CharArray, passwordHash: String): Boolean
    fun needsRehash(passwordHash: String): Boolean
}

interface AuthenticationService {
    suspend fun hasUsers(): Boolean
    suspend fun createFirstUser(
        bootstrapToken: String,
        username: String,
        displayName: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession

    suspend fun login(
        username: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession

    suspend fun authenticate(sessionToken: String): AuthenticatedUser?
    suspend fun logout(sessionToken: String)
    suspend fun revokeAllSessions(userId: User.Id)
}

interface FirstUserBootstrapToken {
    fun currentToken(): String?
    fun consume(candidate: String): Boolean
    fun disable()
}
