package me.rerere.ai.provider.providers

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionRequest
import me.rerere.ai.provider.providers.openai.ChatCompletionResponse
import me.rerere.ai.provider.providers.openai.ChatMessage
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.ApiKeyRotator
import me.rerere.ai.util.sse.readSse
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

object ClaudeProvider : Provider<ProviderSetting.Claude> {
    private val client by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    }

    override suspend fun listModels(
        context: Context,
        providerSetting: ProviderSetting.Claude
    ): List<Model> {
        // Claude API不直接提供模型列表，这里返回一些常用模型
        return listOf(
            Model("claude-3-opus-20240229"),
            Model("claude-3-sonnet-20240229"),
            Model("claude-3-haiku-20240307"),
            Model("claude-2.1"),
            Model("claude-2.0"),
            Model("claude-instant-1.2")
        )
    }

    override suspend fun generateText(
        context: Context,
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val response = client.post {
            url(providerSetting.baseUrl.ifBlank { "https://api.anthropic.com/v1/messages" })
            header("x-api-key", ApiKeyRotator.getNextApiKey(context, "claude_${providerSetting.id}", providerSetting.apiKey))
            header("anthropic-version", "2023-06-01")
            header("Content-Type", "application/json")
            setBody(ChatCompletionRequest(
                model = params.model.modelId,
                messages = messages.map { message -> 
                    ChatMessage(
                        role = when(message.role) {
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            MessageRole.SYSTEM -> "system"
                            MessageRole.TOOL -> "tool"
                        },
                        content = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
                    )
                },
                max_tokens = params.maxTokens ?: 1000,
                temperature = params.temperature ?: 0.7f,
                stream = false
            ))
        }.body<ChatCompletionResponse>()
        
        return MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(response.content.firstOrNull()?.text ?: ""))
                    ),
                    finishReason = null
                )
            ),
            usage = null
        )
    }

    override suspend fun streamText(
        context: Context,
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = flow {
        val response = client.post {
            url(providerSetting.baseUrl.ifBlank { "https://api.anthropic.com/v1/messages" })
            header("x-api-key", ApiKeyRotator.getNextApiKey(context, "claude_${providerSetting.id}", providerSetting.apiKey))
            header("anthropic-version", "2023-06-01")
            header("Content-Type", "application/json")
            setBody(ChatCompletionRequest(
                model = params.model.modelId,
                messages = messages.map { message -> 
                    ChatMessage(
                        role = when(message.role) {
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            MessageRole.SYSTEM -> "system"
                            MessageRole.TOOL -> "tool"
                        },
                        content = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
                    )
                },
                max_tokens = params.maxTokens ?: 1000,
                temperature = params.temperature ?: 0.7f,
                stream = true
            ))
        }
        readSse(response) { event ->
            when(event.event) {
                "content_block_delta" -> {
                    val data = Json.decodeFromString<ContentBlockDelta>(event.data)
                    emit(MessageChunk(
                        id = Uuid.random().toString(),
                        model = params.model.modelId,
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(UIMessagePart.Text(data.delta.text))
                                ),
                                message = null,
                                finishReason = null
                            )
                        ),
                        usage = null
                    ))
                }
                "message_stop" -> {
                    emit(MessageChunk(
                        id = Uuid.random().toString(),
                        model = params.model.modelId,
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = null,
                                message = null,
                                finishReason = "stop"
                            )
                        ),
                        usage = null
                    ))
                    return@readSse
                }
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class ContentBlockDelta(
    val delta: Delta
) {
    @kotlinx.serialization.Serializable
    data class Delta(
        val text: String,
        val type: String
    )
}
