package com.gromozeka.mobile.worker

import java.util.UUID

internal actual fun randomMobileWorkerEventId(): String = UUID.randomUUID().toString()
