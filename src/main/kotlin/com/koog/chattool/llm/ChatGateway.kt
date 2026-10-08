package com.koog.chattool.llm

import com.koog.chattool.model.ChatMessage

/**
 * Входной запрос к LLM: необязательный системный промпт + сообщения диалога.
 * Используется эндпоинтом POST /llm/chat (только для верификации шлюза).
 */
data class ChatRequest(
    val systemPrompt: String? = null,
    val messages: List<ChatMessage>,
)

/** Результат вызова LLM: текст ответа, причина завершения и счётчики токенов. */
data class ChatResult(
    val requestId: String,  // UUID вызова: связывает события журнала llm.request/llm.response/llm.error
    val text: String,
    val finishReason: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)

/**
 * Единый шлюз всех вызовов LLM в проекте.
 *
 * Требование проекта: вызовы LLM выполняются ТОЛЬКО через фреймворк Koog —
 * реализация [KoogLlmGateway] оборачивает Koog PromptExecutor, а [LlmLoggingGateway]
 * добавляет обязательное JSONL-логирование запросов/ответов (см. T6).
 */
interface ChatGateway {
    suspend fun chat(request: ChatRequest): ChatResult
}
