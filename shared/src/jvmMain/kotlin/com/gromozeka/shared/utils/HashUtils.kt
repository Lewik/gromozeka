@file:JvmName("JvmHashUtils")

package com.gromozeka.shared.utils

import java.io.File

fun File.sha256(): String = readBytes().sha256()
