package com.gromozeka.bot

import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ModelsConfig {
    @Bean
    fun openAiApiKey(@Value("\${spring.ai.openai.api-key}") apiKey: String) = apiKey

    @Bean
    fun openAiAudioTranscriptionModel(openAiApiKey: String): OpenAiAudioTranscriptionModel {
        val openAiAudioApi = OpenAiAudioApi.builder().apiKey(openAiApiKey).build()
        return OpenAiAudioTranscriptionModel(openAiAudioApi)
    }

    @Bean
    fun openAiChatModel(openAiApiKey: String): OpenAiChatModel {
        val openAiApi = OpenAiApi.builder()
            .apiKey(openAiApiKey)
            .build()

//        val model = "gpt-4.1"
//        val model = OpenAiApi.ChatModel.GPT_4_O
        val model = OpenAiApi.ChatModel.GPT_4_O_MINI  //туповато и дешево
////            .model("gpt-4.5-preview") //ДОРОГО!!! и ооооочень долго
        return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder().model(model).build())
            .build()
    }
}