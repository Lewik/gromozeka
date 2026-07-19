package com.gromozeka.domain.tool

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolParameter(
    val description: String = "",
    val minimum: Long = Long.MIN_VALUE,
    val maximum: Long = Long.MAX_VALUE,
)
