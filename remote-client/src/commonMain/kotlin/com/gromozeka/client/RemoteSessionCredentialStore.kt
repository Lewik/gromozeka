package com.gromozeka.client

interface RemoteSessionCredentialStore {
    fun load(serverKey: String): String?

    fun save(serverKey: String, encodedSession: String?)
}
