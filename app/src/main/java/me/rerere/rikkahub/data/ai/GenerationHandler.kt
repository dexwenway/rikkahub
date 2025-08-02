package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.InputMessageTransformer
import me.rerere.ai.ui.MessageTransformer
import me.rerere.ai.ui.OutputMessageTransformer
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.onGenerationFinish
import me.rerere.ai.ui.transforms
import me.rerere.ai.ui.truncate
import me.rerere.ai.ui.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository

private const val TAG = "GenerationHandler"

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant? = null,
        memories: (suspend () -> List<AssistantMemory>)? = null,
        tools: List<Tool> = emptyList(),
        truncateIndex: Int = -1,
        maxSteps: Int = 256,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = ProviderManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant?.enableMemory == true) {
                    buildMemoryTools(
                        onCreation = { content ->
                            memoryRepo.addMemory(assistant.id.toString(), content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            generateInternal(
                assistant = assistant,
                messages = messages,
                onUpdateMessages = {
                    messages = it.transforms(
                        outputTransformers,
                        context,
                        model
                    )
                    emit(
                        GenerationChunk.Messages(
                            messages.visualTransforms(outputTransformers, context, model)
                        )
                    )
                },
                transformers = inputTransformers,
                model = model,
                providerImpl = providerImpl,
                provider = provider,
                tools = toolsInternal,
                memories = memories?.invoke() ?: emptyList(),
                truncateIndex = truncateIndex,
                stream = assistant?.streamOutput ?: true
            )
            messages = messages.visualTransforms(outputTransformers, context, model)
            messages = messages.onGenerationFinish(outputTransformers, context, model)
            emit(GenerationChunk.Messages(messages))

            val toolCalls = messages.last().getToolCalls()
            if (toolCalls.isEmpty()) {
                // no tool calls, break
                break
            }
            // handle tool calls
            val results = arrayListOf<UIMessagePart.ToolResult>()
            toolCalls.forEach { toolCall ->
                runCatching {
                    val tool = toolsInternal.find { tool -> tool.name == toolCall.toolName }
                        ?: error("Tool ${toolCall.toolName} not found")
                    val args = json.parseToJsonElement(toolCall.arguments.ifBlank { "{}" })
                    Log.i(TAG, "generateText: executing tool ${tool.name} with args: $args")
                    val result = tool.execute(args)
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        content = result,
                        arguments = args,
                        metadata = toolCall.metadata
                    )
                }.onFailure {
                    it.printStackTrace()
                    results += UIMessagePart.ToolResult(
                        toolName = toolCall.toolName,
                        toolCallId = toolCall.toolCallId,
                        metadata = toolCall.metadata,
                        content = buildJsonObject {
                            put(
                                "error",
                                JsonPrimitive(buildString {
                                    append("[${it.javaClass.name}] ${it.message}")
                                    append("\n${it.stackTraceToString()}")
                                })
                            )
                        },
                        arguments = runCatching {
                            json.parseToJsonElement(toolCall.arguments)
                        }.getOrElse { JsonObject(emptyMap()) }
                    )
                }
            }
            messages = messages + UIMessage(
                role = MessageRole.TOOL,
                parts = results
            )
            emit(GenerationChunk.Messages(messages.transforms(outputTransformers, context, model)))
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant?,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        truncateIndex: Int,
        stream: Boolean
    ) {
        val internalMessages = buildList {
            if (assistant != null) {
                // 如果存在助手，构造系统消息
                val system = buildString {
                    // 如果助手有系统提示，则添加到消息中
                    if (assistant.systemPrompt.isNotBlank()) {
                        append(assistant.systemPrompt)
                    }

                    // 记忆
                    if (assistant.enableMemory) {
                        appendLine()
                        append(buildMemoryPrompt(memories))
                    }
                    if (assistant.enableRecentChatsReference) {
                        appendLine()
                        append(buildRecentChatsPrompt(assistant))
                    }

                    // 工具prompt
                    tools.forEach { tool ->
                        appendLine()
                        append(tool.systemPrompt(model))
                    }
                }
                if (system.isNotBlank()) add(UIMessage.system(system))
            }
            addAll(messages.truncate(truncateIndex).limitContext(assistant?.contextMessageSize ?: 32))
        }.transforms(transformers, context, model)

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant?.temperature,
            topP = assistant?.topP,
            maxTokens = assistant?.maxTokens,
            tools = tools,
            thinkingBudget = assistant?.thinkingBudget,
            customHeaders = buildList {
                assistant?.customHeaders?.let { addAll(it) }
                addAll(model.customHeaders)
            },
            customBody = buildList {
                assistant?.customBodies?.let { addAll(it) }
                addAll(model.customBodies)
            }
        )
        if (stream) {
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun buildMemoryTools(
        onCreation: suspend (String) -> AssistantMemory,
        onUpdate: suspend (Int, String) -> AssistantMemory,
        onDelete: suspend (Int) -> Unit
    ) = listOf(
        Tool(
            name = "create_memory",
            description = "create a memory record",
            parameters = InputSchema.Obj(
                properties = buildJsonObject {
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record")
                    })
                },
                required = listOf("content")
            ),
            execute = {
                val params = it.jsonObject
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
            }
        ),
        Tool(
            name = "edit_memory",
            description = "update a memory record",
            parameters = InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record")
                    })
                },
                required = listOf("id", "content"),
            ),
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                val content =
                    params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                json.encodeToJsonElement(
                    AssistantMemory.serializer(), onUpdate(id, content)
                )
            }
        ),
        Tool(
            name = "delete_memory",
            description = "delete a memory record",
            parameters = InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record")
                    })
                },
                required = listOf("id")
            ),
            execute = {
                val params = it.jsonObject
                val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                onDelete(id)
                JsonPrimitive(true)
            }
        )
    )

    private fun buildMemoryPrompt(memories: List<AssistantMemory>) =
        buildString {
            append(
                """
                ## 记忆功能
                你是一个无状态的大模型，你**无法存储记忆**，因此为了记住信息，你需要使用**记忆工具**。
                记忆工具允许你(助手)存储多条信息(record)以便在跨对话聊天中记住信息。
                你可以使用`create_memory`, `edit_memory`和`delete_memory`工具创建，更新或删除记忆。
                - 如果记忆内没有相关信息，你需要调用`create_memory`工具来创建一个记忆记录。
                - 如果已经有相关记录，请调用`edit_memory`工具来更新一个记忆记录。
                - 如果一个记忆过时或者无用了，请调用`delete_memory`工具来删除一个记忆记录。
                这些记忆会自动包含在未来的对话上下文中，在<memories>标签内。
                请勿在记忆中存储敏感信息，敏感信息包括：用户的民族、宗教信仰、性取向、政治观点及党派归属、性生活、犯罪记录等。
                在与用户聊天过程中，你可以像一个私人秘书一样**主动的**记录用户相关的信息到记忆里，包括但不限于：
                - 用户昵称/姓名
                - 年龄/性别/兴趣爱好
                - 计划事项等
                - 聊天风格偏好
                - 工作相关
                - 首次聊天时间
                - ...
                请主动调用工具记录，而不是需要用户要求。
                记忆如果包含日期信息，请包含在内，请使用绝对时间格式，并且当前时间是 {cur_datetime}。
                无需告知用户你已更改记忆记录，也不要在对话中直接显示记忆内容，除非用户主动要求。
                相似或相关的记忆应合并为一条记录，而不要重复记录，过时记录应删除。
                你可以在和用户闲聊的时候暗示用户你能记住东西。
            """.trimIndent()
            )
            append("\n<memories>\n")
            memories.forEach { memory ->
                append("<record>\n")
                append("<id>${memory.id}</id>")
                append("<content>${memory.content}</content>")
                append("</record>\n")
            }
            append("</memories>\n")
        }

    private suspend fun buildRecentChatsPrompt(assistant: Assistant): String {
        val recentConversations = conversationRepo.getRecentConversations(
            assistantId = assistant.id,
            limit = 10,
        )
        if (recentConversations.isNotEmpty()) {
            return buildString {
                append("## 最近的对话\n")
                append("这是用户最近的一些对话，你可以参考这些对话了解用户偏好:\n")
                append("\n<recent_chats>\n")
                recentConversations.forEach { conversation ->
                    append("<conversation>\n")
                    append("  <title>${conversation.title}</title>")
                    append("</conversation>\n")
                }
                append("</recent_chats>\n")
            }
        }
        return ""
    }
}
