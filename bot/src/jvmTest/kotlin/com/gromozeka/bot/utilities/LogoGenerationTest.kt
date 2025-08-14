package com.gromozeka.bot

import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.junit.jupiter.api.Disabled
import java.io.File
import kotlin.test.Test

/**
 * Test for generating logo PNG files from SVG source using Apache Batik.
 *
 * Run this test to regenerate all logo files:
 * ./gradlew :bot:jvmTest --tests LogoGenerationTest.generateLogos
 */
class LogoGenerationTest {

    // DISABLED - This test is for logo generation only and should not run in CI
    @Disabled
    @Test
    fun generateLogos() {

        val projectDir = File("").absoluteFile
        val svgFile = File(projectDir, "src/jvmMain/resources/logo.svg")
        val logosDir = File(projectDir, "src/jvmMain/resources/logos")

        require(svgFile.exists()) { "SVG file not found: ${svgFile.absolutePath}" }
        logosDir.mkdirs()

        logosDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != "README.md") {
                file.delete()
                println("🗑️ Deleted old file: ${file.name}")
            }
        }

        val sizes = listOf(32, 64, 128, 256, 512)

        sizes.forEach { size ->
            val fileName = "logo-${size}x${size}.png"
            val pngFile = File(logosDir, fileName)

            convertSvgToPng(svgFile, pngFile, size.toFloat(), size.toFloat())
            println("✅ Generated ${size}x${size}: ${pngFile.name}")

            require(pngFile.exists()) { "PNG file was not created: ${pngFile.absolutePath}" }
        }
    }

    private fun convertSvgToPng(svgFile: File, pngFile: File, width: Float, height: Float) {
        val transcoder = PNGTranscoder()
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width)
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height)

        svgFile.inputStream().use { input ->
            pngFile.outputStream().use { output ->
                val transcoderInput = TranscoderInput(input)
                val transcoderOutput = TranscoderOutput(output)
                transcoder.transcode(transcoderInput, transcoderOutput)
            }
        }
    }
}