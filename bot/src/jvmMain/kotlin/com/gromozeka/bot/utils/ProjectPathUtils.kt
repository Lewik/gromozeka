package com.gromozeka.bot.utils

import java.io.File

fun File.decodeProjectPath() = name.replace("-", "/")

fun File.isSessionFile() = extension == "jsonl" && !name.endsWith(".backup")
