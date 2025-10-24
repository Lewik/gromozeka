package com.gromozeka.shared.logging

import klog.KLoggers
import kotlin.reflect.full.functions

private class KlogLoggerAdapter(private val klogger: Any) : Logger {
    override fun info(message: String) {
        klogger::class.functions.first { it.name == "info" }.call(klogger, message)
    }

    override fun warn(message: String) {
        klogger::class.functions.first { it.name == "warn" }.call(klogger, message)
    }

    override fun debug(message: String) {
        klogger::class.functions.first { it.name == "debug" }.call(klogger, message)
    }

    override fun error(message: String) {
        klogger::class.functions.first { it.name == "error" }.call(klogger, message)
    }
}

actual fun <T : Any> logger(clazz: T): Logger =
    KlogLoggerAdapter(KLoggers.logger(clazz::class.java))
