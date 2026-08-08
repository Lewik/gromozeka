package com.gromozeka.mobile.worker

import platform.Foundation.NSUUID

internal actual fun randomMobileWorkerEventId(): String = NSUUID().UUIDString.lowercase()
