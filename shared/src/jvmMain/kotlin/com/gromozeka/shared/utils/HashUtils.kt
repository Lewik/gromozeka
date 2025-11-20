package com.gromozeka.shared.utils

import java.io.File
import java.security.MessageDigest

fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

fun String.sha256(): String = toByteArray().sha256()

fun File.sha256(): String = readBytes().sha256()