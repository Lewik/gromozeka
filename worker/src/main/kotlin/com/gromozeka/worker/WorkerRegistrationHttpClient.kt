package com.gromozeka.worker

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Path

internal fun workerRegistrationHttpClient(caCertificatePath: Path?): HttpClient = HttpClient(CIO) {
    followRedirects = false
    caCertificatePath?.let { path ->
        engine { https { trustManager = workerTrustManager(path.toString()) } }
    }
}
