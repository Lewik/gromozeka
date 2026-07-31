package com.gromozeka.client

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.isSecure
import io.ktor.util.date.GMTDate
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class PersistentSessionCookiesStorage(
    remoteUrl: String,
    private val credentialStore: RemoteSessionCredentialStore,
    private val clock: () -> Long = ::getTimeMillis,
) : CookiesStorage {
    private val serverUrl = Url(websocketUrlToHttpBase(remoteUrl))
    private val serverKey = serverUrl.toString().trimEnd('/')
    private val delegate = AcceptAllCookiesStorage(clock)
    private val initializationMutex = Mutex()
    private var initialized = false

    override suspend fun get(requestUrl: Url): List<Cookie> {
        initialize()
        return delegate.get(requestUrl)
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        initialize()
        delegate.addCookie(requestUrl, cookie)
        if (requestUrl.host != serverUrl.host || cookie.name != SESSION_COOKIE_NAME) {
            return
        }

        val now = clock()
        val expiresAt = cookie.maxAge?.let { now + it * 1_000L }
            ?: cookie.expires?.timestamp
        val deleted = cookie.value.isEmpty() || cookie.maxAge?.let { it <= 0 } == true ||
            expiresAt?.let { it <= now } == true
        credentialStore.save(
            serverKey,
            if (deleted) {
                null
            } else {
                json.encodeToString(StoredRemoteSession(cookie.value, expiresAt))
            },
        )
    }

    override fun close() {
        delegate.close()
    }

    private suspend fun initialize() {
        initializationMutex.withLock {
            if (initialized) {
                return@withLock
            }

            val encoded = credentialStore.load(serverKey)
            if (encoded == null) {
                initialized = true
                return@withLock
            }
            val stored = runCatching { json.decodeFromString<StoredRemoteSession>(encoded) }
                .getOrElse {
                    credentialStore.save(serverKey, null)
                    initialized = true
                    return@withLock
                }
            val now = clock()
            if (stored.value.isEmpty() || stored.expiresAtEpochMillis?.let { it <= now } == true) {
                credentialStore.save(serverKey, null)
                initialized = true
                return@withLock
            }
            delegate.addCookie(
                serverUrl,
                Cookie(
                    name = SESSION_COOKIE_NAME,
                    value = stored.value,
                    expires = stored.expiresAtEpochMillis?.let(::GMTDate),
                    domain = serverUrl.host,
                    path = "/",
                    secure = serverUrl.protocol.isSecure(),
                    httpOnly = true,
                ),
            )
            initialized = true
        }
    }

    @Serializable
    private data class StoredRemoteSession(
        val value: String,
        val expiresAtEpochMillis: Long?,
    )

    private companion object {
        const val SESSION_COOKIE_NAME = "gromozeka_session"
        val json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        }
    }
}
