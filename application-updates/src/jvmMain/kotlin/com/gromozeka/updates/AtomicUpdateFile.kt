package com.gromozeka.updates

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun moveUpdateFile(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
