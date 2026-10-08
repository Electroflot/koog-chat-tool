package com.koog.chattool.routes

import com.koog.chattool.config.KoogConfig
import com.koog.chattool.llm.ChatGateway
import com.koog.chattool.llm.ChatRequest
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.ChatRole
import com.koog.chattool.model.InvalidJsonException
import com.koog.chattool.model.ValidationException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Верификационный эндпоинт POST /llm/chat — единственная точка, где сервис САМ
 * вызывает LLM. По умолчанию ОТКЛЮЧЁН (app.llm.exposeChatEndpoint = false),
 * включается только для QA/верификации шлюза. Каждый вызов проходит через
 * LlmLoggingGateway и обязательно пишется в журнал logs/llm-requests.log.
 */
fun Route.llmRoutes(
    gateway: ChatGateway,
    llmConfig: KoogConfig,
    maxRequestBodyBytes: Long,
    json: Json,
) {
    // Эндпоинт не регистрируется вообще, если не включён в конфигурации.
    if (!llmConfig.exposeChatEndpoint) return

    post("/llm/chat") {
        // Тот же жёсткий лимит тела, что и у /tools/save-chat (защита от OOM).
        val bodyBytes = call.readBodyWithLimit(maxRequestBodyBytes)
        val request = try {
            json.decodeFromString<LlmChatRequest>(bodyBytes.decodeToString())
        } catch (e: SerializationException) {
            throw InvalidJsonException("Тело запроса не соответствует схеме: ${e.message}")
        }

        // В запросе к LLM допустимы только роли system и user.
        val unsupported = request.messages.firstOrNull { it.role !in SUPPORTED_ROLES }
        if (unsupported != null) {
            throw ValidationException(
                "Роль '${unsupported.role}' не поддерживается; допустимы: system, user",
            )
        }

        // Вызов LLM через шлюз (Koog + логирование) — см. llm/LlmLoggingGateway.kt.
        val result = gateway.chat(
            ChatRequest(systemPrompt = request.systemPrompt, messages = request.messages),
        )

        call.respond(
            LlmChatResponse(
                text = result.text,
                finishReason = result.finishReason,
                usage = LlmUsage(
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens,
                    totalTokens = result.totalTokens,
                ),
                requestId = result.requestId,
            ),
        )
    }
}

/** Тело запроса POST /llm/chat (для верификации шлюза). */
@Serializable
data class LlmChatRequest(
    val systemPrompt: String? = null,
    val messages: List<ChatMessage>,
)

/** Ответ POST /llm/chat: текст, причина завершения, токены и id вызова в журнале. */
@Serializable
data class LlmChatResponse(
    val text: String,
    val finishReason: String?,
    val usage: LlmUsage,
    val requestId: String,
)

@Serializable
data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)

/** Роли, допустимые в верификационном запросе к LLM. */
private val SUPPORTED_ROLES = setOf(ChatRole.SYSTEM, ChatRole.USER)
