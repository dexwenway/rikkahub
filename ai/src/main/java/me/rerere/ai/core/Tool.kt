package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: InputSchema? = null,
    val systemPrompt: (model: Model) -> String = { "" },
    val execute: suspend (JsonElement) -> JsonElement
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
