package com.gromozeka.mobile.worker

interface MobileWorkerStorage {
    fun readState(): String?

    fun writeState(value: String)

    fun readCredential(): String?

    fun writeCredential(value: String)

    fun clearCredential()
}
