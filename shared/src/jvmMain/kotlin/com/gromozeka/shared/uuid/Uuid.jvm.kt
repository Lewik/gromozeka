package com.gromozeka.shared.uuid

import com.github.f4b6a3.uuid.UuidCreator

actual fun uuid7(): String =
    UuidCreator.getTimeOrderedEpoch().toString()
