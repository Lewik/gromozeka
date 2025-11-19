package com.gromozeka.infrastructure.db.persistence

import kotlinx.datetime.Instant as KotlinxInstant
import kotlin.time.Instant as KotlinInstant

fun KotlinInstant.toKotlinx(): KotlinxInstant = 
    KotlinxInstant.fromEpochMilliseconds(this.toEpochMilliseconds())

fun KotlinxInstant.toKotlin(): KotlinInstant = 
    KotlinInstant.fromEpochMilliseconds(this.toEpochMilliseconds())
