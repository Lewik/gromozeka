package com.gromozeka.server

import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.domain.service.PasswordHasher
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

@Service
class Argon2PasswordHasher : PasswordHasher {
    private val encoder = Argon2PasswordEncoder(
        SALT_LENGTH_BYTES,
        HASH_LENGTH_BYTES,
        PARALLELISM,
        MEMORY_KIB,
        ITERATIONS,
    )

    override fun hash(password: CharArray): String =
        requireNotNull(encoder.encode(password.concatToString())) {
            "Argon2 encoder returned no password hash"
        }

    override fun verify(password: CharArray, passwordHash: String): Boolean =
        encoder.matches(password.concatToString(), passwordHash)

    override fun needsRehash(passwordHash: String): Boolean =
        encoder.upgradeEncoding(passwordHash)

    private companion object {
        const val SALT_LENGTH_BYTES = 16
        const val HASH_LENGTH_BYTES = 32
        const val PARALLELISM = 1
        const val MEMORY_KIB = 19 * 1024
        const val ITERATIONS = 2
    }
}

@Service
class InMemoryFirstUserBootstrapToken : FirstUserBootstrapToken {
    private val token = AtomicReference(generateToken())

    override fun currentToken(): String? = token.get()

    override fun consume(candidate: String): Boolean {
        val current = token.get() ?: return false
        return constantTimeEquals(current, candidate) && token.compareAndSet(current, null)
    }

    override fun disable() {
        token.set(null)
    }

    private fun generateToken(): String =
        ByteArray(TOKEN_BYTES)
            .also(SecureRandom()::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun constantTimeEquals(expected: String, candidate: String): Boolean {
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
        var difference = expectedBytes.size xor candidateBytes.size
        val length = maxOf(expectedBytes.size, candidateBytes.size)
        for (index in 0 until length) {
            val expectedByte = expectedBytes.getOrElse(index) { 0 }
            val candidateByte = candidateBytes.getOrElse(index) { 0 }
            difference = difference or (expectedByte.toInt() xor candidateByte.toInt())
        }
        return difference == 0
    }

    private companion object {
        const val TOKEN_BYTES = 24
    }
}
