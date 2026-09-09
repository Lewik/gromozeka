package com.gromozeka.presentation.ui

import com.gromozeka.shared.localization.TranslationFormatter

fun String.format(vararg args: Any?): String = TranslationFormatter.positional(this, args.toList())
