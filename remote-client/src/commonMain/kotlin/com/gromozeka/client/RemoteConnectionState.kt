package com.gromozeka.client

data class RemoteConnectionState(
    val status: Status,
    val reconnectAttempt: Int = 0,
    val lastError: String? = null,
) {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        OFFLINE,
        CLOSED,
    }
}
