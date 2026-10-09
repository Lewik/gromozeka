package com.gromozeka.server.testsupport.app

import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory

/** A manually bootstrapped E2E context must not auto-scan unrelated @TestConfiguration classes.
 * Explicit ServerTestHarness sources are still registered normally, outside component scanning.
 */
class E2eTestConfigurationFilter : TypeExcludeFilter() {
    override fun match(reader: MetadataReader, factory: MetadataReaderFactory): Boolean =
        reader.annotationMetadata.hasAnnotation("org.springframework.boot.test.context.TestConfiguration")
    override fun equals(other: Any?) = other is E2eTestConfigurationFilter
    override fun hashCode() = E2eTestConfigurationFilter::class.java.hashCode()
}
