package com.gromozeka.application.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.coroutines.CoroutineContext

@Configuration
class ApplicationCoroutineConfiguration {
    @Bean(destroyMethod = "close")
    @Qualifier("applicationScope")
    fun applicationScope(): CoroutineScope = SpringManagedCoroutineScope()
}

private class SpringManagedCoroutineScope : CoroutineScope, AutoCloseable {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default

    override fun close() {
        cancel()
    }
}
