package com.gromozeka.domain.service

class ControlPlaneUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ControlPlaneRequestRejectedException(val code: String, message: String) : RuntimeException(message)
