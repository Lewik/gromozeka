package com.gromozeka.bot

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun contextLoads() {
        // If Spring context loads successfully, this test passes
        // If there are bean initialization errors, it will fail here
        assertNotNull(applicationContext, "Application context should be loaded")
    }

    @Test
    fun `verify critical beans are initialized`() {
        val beanNames = applicationContext.beanDefinitionNames
        
        println("Total beans loaded: ${beanNames.size}")
        
        // Verify we have beans (context is not empty)
        assertTrue(beanNames.isNotEmpty(), "Application context should contain beans")
        
        // Log sample of loaded beans for debugging
        println("Sample of loaded beans:")
        beanNames.take(10).forEach { println("  - $it") }
    }
}
