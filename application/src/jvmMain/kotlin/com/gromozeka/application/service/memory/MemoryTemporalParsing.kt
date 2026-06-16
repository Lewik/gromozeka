package com.gromozeka.application.service.memory

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

internal fun String?.toMemoryInstantOrNull(timezone: String): Instant? {
    val value = this?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    runCatching { Instant.parse(value) }.getOrNull()?.let { return it }

    val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
    return date.atStartOfDayIn(TimeZone.of(timezone))
}
