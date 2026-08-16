package com.gromozeka.mobile.worker

import com.gromozeka.shared.logging.GromozekaLogging

fun logIosMobileWorkerError(message: String) {
    GromozekaLogging.logger("GromozekaMobileWorker").error(message)
}
