// 已经正确实现了 listModels 方法，包含 context 参数
// 已经正确实现了 streamText 方法，包含 context 参数
// 已经正确实现了 generateText 方法，包含 context 参数
package me.rerere.ai.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.await
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

import android.content.Context
import me.rerere.ai.util.ApiKeyRotator

object OpenAIProvider : Provider<ProviderSetting.OpenAI> {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        })
        .build()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client)
    private val responseAPI = ResponseAPI(client = client)

    private fun getRandomApiKey(apiKey: String): String {
        val keys = apiKey.split(",")
        return keys.random()
    }
    
    // 新增顺序轮询方法，替换原有的随机选择方法
    private fun getNextApiKey(context: Context, providerSetting: ProviderSetting.OpenAI): String {
        return ApiKeyRotator.getNextApiKey(
            context,
            "openai_${providerSetting.id}",
            providerSetting.apiKey
        )
    }
    
    override suspend fun listModels(
        context: Context, 
        providerSetting: ProviderSetting.OpenAI
    ): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer ${getNextApiKey(context, providerSetting)}")
                .get()
                .build()

            val response =
                client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun streamText(
        context: Context,
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting.copy(apiKey = getNextApiKey(context, providerSetting)),
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting.copy(apiKey = getNextApiKey(context, providerSetting)),
            messages = messages,
            params = params
        )
    }
    
    override suspend fun generateText(
        context: Context,
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting.copy(apiKey = getNextApiKey(context, providerSetting)),
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting.copy(apiKey = getNextApiKey(context, providerSetting)),
            messages = messages,
            params = params
        )
    }
}
