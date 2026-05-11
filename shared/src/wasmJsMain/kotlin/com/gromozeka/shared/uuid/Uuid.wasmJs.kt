package com.gromozeka.shared.uuid

import kotlinx.datetime.Clock
import kotlin.random.Random

actual fun uuid7(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
        .toString(16)
        .padStart(12, '0')
        .takeLast(12)
    val random = (1..18).joinToString("") { Random.nextInt(16).toString(16) }
    val variant = (8 + Random.nextInt(4)).toString(16)

    return buildString {
        append(timestamp.substring(0, 8))
        append('-')
        append(timestamp.substring(8, 12))
        append("-7")
        append(random.substring(0, 3))
        append('-')
        append(variant)
        append(random.substring(3, 6))
        append('-')
        append(random.substring(6, 18))
    }
}
