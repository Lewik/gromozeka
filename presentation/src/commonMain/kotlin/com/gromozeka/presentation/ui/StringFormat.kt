package com.gromozeka.presentation.ui

fun String.format(vararg args: Any?): String {
    var result = this
    args.forEach { argument ->
        result = result.replaceFirst(Regex("%[sd]"), argument.toString())
    }
    return result
}
