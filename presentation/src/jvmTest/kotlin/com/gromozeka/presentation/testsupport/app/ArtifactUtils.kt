package com.gromozeka.presentation.testsupport.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun String.sanitizePathSegment(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "unknown" }

internal fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val destination = target.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination)
            } else {
                destination.parent?.let(Files::createDirectories)
                Files.copy(
                    path,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
        }
    }
}
