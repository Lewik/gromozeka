package com.gromozeka.shared.logging

interface Logger {
    fun info(message: String)
    fun warn(message: String)
    fun debug(message: String)
    fun error(message: String)
}

expect fun <T : Any> logger(clazz: T): Logger
