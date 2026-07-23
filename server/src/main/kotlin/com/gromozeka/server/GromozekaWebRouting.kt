package com.gromozeka.server

import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.ETagProvider
import io.ktor.server.http.content.default
import io.ktor.server.http.content.preCompressed
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.Routing
import java.io.File

private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
private const val REVALIDATED_CACHE_CONTROL = "no-cache"
private val contentHashedAssetName = Regex("""^[0-9a-f]{20,64}\.(wasm|js|css)$""")

internal fun Routing.gromozekaWeb(webRoot: File) {
    staticFiles("/", webRoot) {
        preCompressed(
            CompressedFileType.BROTLI,
            CompressedFileType.GZIP,
        )
        etag(ETagProvider.StrongSha256)
        modify { file, call ->
            call.response.headers.append(
                HttpHeaders.CacheControl,
                if (file.isContentHashedAsset()) IMMUTABLE_CACHE_CONTROL else REVALIDATED_CACHE_CONTROL,
            )
        }
        default("index.html")
    }
}

private fun File.isContentHashedAsset(): Boolean {
    val uncompressedName = name.removeSuffix(".br").removeSuffix(".gz")
    return contentHashedAssetName.matches(uncompressedName)
}
