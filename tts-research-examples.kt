/**
 * TTS Integration Examples for Gromozeka
 * Research results for commercial desktop applications with Russian support
 */

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

// ============================================================================
// macOS Native TTS via `say` command
// ============================================================================

/**
 * macOS TTS wrapper using the native `say` command
 * 
 * WARNING: Apple's TTS voices are NOT licensed for commercial use!
 * macOS Software License Agreement restricts System Voices to:
 * "(i) while running the Apple Software and (ii) to create your own original 
 * content and projects for your personal, non-commercial use"
 */
class MacOSTTS {
    
    /**
     * Get available voices from macOS `say` command
     */
    suspend fun getAvailableVoices(): List<String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("say", "-v", "?").start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            
            output.lines()
                .filter { it.isNotBlank() }
                .map { line -> line.split(" ")[0] } // Extract voice name
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Speak text using macOS TTS
     * Russian voice: "Milena" (if available)
     */
    suspend fun speak(
        text: String, 
        voice: String = "Milena", // Russian voice
        rate: Int = 200, // words per minute
        outputFile: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = mutableListOf("say", "-v", voice, "-r", rate.toString())
            
            if (outputFile != null) {
                command.addAll(listOf("-o", outputFile))
            }
            
            command.add(text)
            
            val process = ProcessBuilder(command).start()
            process.waitFor(30, TimeUnit.SECONDS) == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generate audio file using macOS TTS
     */
    suspend fun generateAudioFile(
        text: String,
        outputPath: String,
        voice: String = "Milena"
    ): Boolean = speak(text, voice, outputFile = outputPath)
}

// ============================================================================
// Festival TTS (BSD License - Commercial Use OK)
// ============================================================================

/**
 * Festival TTS wrapper
 * License: BSD-like (commercial use allowed)
 * Russian support: Available via festvox-ru package
 */
class FestivalTTS {
    
    /**
     * Check if Festival is installed
     */
    fun isInstalled(): Boolean {
        return try {
            val process = ProcessBuilder("festival", "--version").start()
            process.waitFor(5, TimeUnit.SECONDS) == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Speak text using Festival TTS
     */
    suspend fun speak(
        text: String,
        voice: String = "msu_ru_nsh_clunits" // Russian voice
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create temporary file for text
            val tempFile = File.createTempFile("festival_", ".txt")
            tempFile.writeText(text, Charsets.UTF_8)
            
            val festivalScript = """
                (voice_${voice})
                (SayText "${text.replace("\"", "\\\"")}")
            """.trimIndent()
            
            val scriptFile = File.createTempFile("festival_", ".scm")
            scriptFile.writeText(festivalScript)
            
            val process = ProcessBuilder("festival", "-b", scriptFile.absolutePath).start()
            val success = process.waitFor(30, TimeUnit.SECONDS) == 0
            
            tempFile.delete()
            scriptFile.delete()
            
            success
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generate WAV file using Festival TTS
     */
    suspend fun generateWavFile(
        text: String,
        outputPath: String,
        voice: String = "msu_ru_nsh_clunits"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val festivalScript = """
                (voice_${voice})
                (utt.save.wave 
                    (SayText "${text.replace("\"", "\\\"")}")
                    "${outputPath}")
            """.trimIndent()
            
            val scriptFile = File.createTempFile("festival_", ".scm")
            scriptFile.writeText(festivalScript)
            
            val process = ProcessBuilder("festival", "-b", scriptFile.absolutePath).start()
            val success = process.waitFor(30, TimeUnit.SECONDS) == 0
            
            scriptFile.delete()
            
            success && File(outputPath).exists()
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// eSpeak-NG (GPL v3+ - Limited Commercial Use)
// ============================================================================

/**
 * eSpeak-NG TTS wrapper
 * License: GPL v3+ (requires derivative works to be open source)
 * Russian support: Available
 */
class ESpeakNGTTS {
    
    fun isInstalled(): Boolean {
        return try {
            val process = ProcessBuilder("espeak-ng", "--version").start()
            process.waitFor(5, TimeUnit.SECONDS) == 0
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun speak(
        text: String,
        language: String = "ru", // Russian
        speed: Int = 175, // words per minute
        pitch: Int = 50   // 0-99
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "espeak-ng",
                "-v", language,
                "-s", speed.toString(),
                "-p", pitch.toString(),
                text
            ).start()
            
            process.waitFor(30, TimeUnit.SECONDS) == 0
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun generateWavFile(
        text: String,
        outputPath: String,
        language: String = "ru"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "espeak-ng",
                "-v", language,
                "-w", outputPath,
                text
            ).start()
            
            val success = process.waitFor(30, TimeUnit.SECONDS) == 0
            success && File(outputPath).exists()
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// Google Cloud TTS (Commercial Cloud Service)
// ============================================================================

/**
 * Google Cloud TTS wrapper
 * Requires Google Cloud credentials and internet connection
 * Pricing: Pay per character (free tier: WaveNet: 1M chars/month, Standard: 4M chars/month)
 */
class GoogleCloudTTS {
    // This would require Google Cloud TTS client library
    // implementation would depend on google-cloud-texttospeech dependency
    
    /**
     * Example structure for Google Cloud TTS
     * You would need to add: implementation 'com.google.cloud:google-cloud-texttospeech:...'
     */
    /*
    suspend fun speak(
        text: String,
        languageCode: String = "ru-RU",
        voiceName: String = "ru-RU-Wavenet-A" // Russian female voice
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val textToSpeechClient = TextToSpeechClient.create()
            
            val input = SynthesisInput.newBuilder()
                .setText(text)
                .build()
                
            val voice = VoiceSelectionParams.newBuilder()
                .setLanguageCode(languageCode)
                .setName(voiceName)
                .setSsmlGender(SsmlVoiceGender.FEMALE)
                .build()
                
            val audioConfig = AudioConfig.newBuilder()
                .setAudioEncoding(AudioEncoding.MP3)
                .build()
                
            val response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig)
            response.audioContent.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
    */
}

// ============================================================================
// Azure TTS (Commercial Cloud Service) 
// ============================================================================

/**
 * Azure TTS wrapper
 * Requires Azure Cognitive Services subscription
 * Pricing: Free tier F0 (5M chars/month), Standard S0 ($16/1M chars)
 */
class AzureTTS {
    // This would require Azure Cognitive Services Speech SDK
    // implementation would depend on Microsoft Cognitive Services Speech SDK
    
    /**
     * Example structure for Azure TTS
     * You would need to add Azure Speech SDK dependency
     */
    /*
    suspend fun speak(
        text: String,
        voice: String = "ru-RU-SvetlanaNeural", // Russian female voice
        subscriptionKey: String,
        region: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val config = SpeechConfig.fromSubscription(subscriptionKey, region)
            config.speechSynthesisVoiceName = voice
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Audio16Khz32KBitRateMonoMp3)
            
            val synthesizer = SpeechSynthesizer(config)
            val result = synthesizer.SpeakText(text)
            
            if (result.reason == ResultReason.SynthesizingAudioCompleted) {
                result.audioData
            } else null
        } catch (e: Exception) {
            null
        }
    }
    */
}

// ============================================================================
// Universal TTS Interface for Gromozeka
// ============================================================================

interface TTSEngine {
    suspend fun speak(text: String): Boolean
    suspend fun generateAudioFile(text: String, outputPath: String): Boolean
    fun isAvailable(): Boolean
    val name: String
    val supportsRussian: Boolean
    val isCommercialFriendly: Boolean
}

/**
 * TTS Manager that prioritizes engines based on commercial licensing and quality
 */
class TTSManager {
    private val engines = mutableListOf<TTSEngine>()
    
    init {
        // Register engines in order of preference for commercial use
        // Note: macOS TTS is NOT commercial friendly but included for development/testing
        engines.add(FestivalTTSEngine())
        engines.add(MacOSTTSEngine()) // For development only!
        engines.add(ESpeakNGTTSEngine()) // GPL restrictions
    }
    
    suspend fun speak(text: String): Boolean {
        for (engine in engines) {
            if (engine.isAvailable() && engine.supportsRussian) {
                if (engine.speak(text)) {
                    return true
                }
            }
        }
        return false
    }
    
    suspend fun getCommercialEngines(): List<TTSEngine> {
        return engines.filter { it.isCommercialFriendly && it.isAvailable() }
    }
}

// Implementation wrappers for the interface
private class MacOSTTSEngine : TTSEngine {
    private val tts = MacOSTTS()
    override val name = "macOS TTS"
    override val supportsRussian = true
    override val isCommercialFriendly = false // Important!
    
    override suspend fun speak(text: String) = tts.speak(text)
    override suspend fun generateAudioFile(text: String, outputPath: String) = 
        tts.generateAudioFile(text, outputPath)
    override fun isAvailable() = System.getProperty("os.name").contains("Mac", ignoreCase = true)
}

private class FestivalTTSEngine : TTSEngine {
    private val tts = FestivalTTS()
    override val name = "Festival TTS"
    override val supportsRussian = true
    override val isCommercialFriendly = true // BSD license
    
    override suspend fun speak(text: String) = tts.speak(text)
    override suspend fun generateAudioFile(text: String, outputPath: String) = 
        tts.generateWavFile(text, outputPath)
    override fun isAvailable() = tts.isInstalled()
}

private class ESpeakNGTTSEngine : TTSEngine {
    private val tts = ESpeakNGTTS()
    override val name = "eSpeak-NG"
    override val supportsRussian = true
    override val isCommercialFriendly = false // GPL v3+ restrictions
    
    override suspend fun speak(text: String) = tts.speak(text)
    override suspend fun generateAudioFile(text: String, outputPath: String) = 
        tts.generateWavFile(text, outputPath)
    override fun isAvailable() = tts.isInstalled()
}

// ============================================================================
// Installation Instructions
// ============================================================================

/**
 * Festival TTS Installation on macOS:
 * 
 * 1. Install dependencies:
 *    brew install festival
 *    
 * 2. Install Russian voice:
 *    Download festvox-ru package from http://festvox.org/packed/
 *    Or compile from source with Russian voice support
 *    
 * 3. Test installation:
 *    festival
 *    (voice_msu_ru_nsh_clunits)
 *    (SayText "Привет мир")
 *    
 * eSpeak-NG Installation on macOS:
 * 
 * 1. Install via Homebrew:
 *    brew install espeak-ng
 *    
 * 2. Test Russian support:
 *    espeak-ng -v ru "Привет мир"
 */

// Usage example:
/*
fun main() {
    runBlocking {
        val ttsManager = TTSManager()
        
        println("Available commercial-friendly engines:")
        ttsManager.getCommercialEngines().forEach { engine ->
            println("- ${engine.name}")
        }
        
        val success = ttsManager.speak("Привет из Gromozeka!")
        if (success) {
            println("TTS speech successful")
        } else {
            println("TTS speech failed")
        }
    }
}
*/