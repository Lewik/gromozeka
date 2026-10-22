package com.gromozeka.server

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import kotlin.test.Test
import kotlin.test.assertNull

class SpringServerStartupConfigurationTest {
    @Test
    fun `packaged server configuration does not disable the launcher Servlet web application`() {
        // Spring binds spring.main after SpringApplicationBuilder.web(...). The retired
        // `none` setting silently overrode SERVLET and made main exit without a listener.
        // Leave the choice to the launcher (and explicit non-web test harnesses).
        for (name in listOf("application.yaml", "application.yaml.dist")) {
            val factory = YamlPropertiesFactoryBean().apply {
                setResources(ClassPathResource(name, GromozekaServerApplication::class.java.classLoader))
                afterPropertiesSet()
            }
            assertNull(factory.getObject()?.getProperty("spring.main.web-application-type"), name)
        }
    }
}
