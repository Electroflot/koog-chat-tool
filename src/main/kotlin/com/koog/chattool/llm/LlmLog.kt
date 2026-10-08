package com.koog.chattool.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Структура записей журнала вызовов LLM (logs/llm-requests.log, JSONL).
 *
 * Каждый вызов LLM порождает ровно два события: llm.request (перед вызовом)
 * и llm.response (успех) ИЛИ llm.error (исключение). Все события одного вызова
 * связаны полем requestId. Контракт полей — docs/ARCHITECTURE.md, раздел 9.
 * Поля null в JSON не сериализуются (explicitNulls=false по умолчанию),
 * поэтому каждая строка содержит только поля своего типа события.
 */
object LlmLog {

    /** Кодек журнала: компактный JSON (одна строка на событие). */
    private val json = Json { encodeDefaults = false }

    /** Сериализует событие в одну JSON-строку для записи в журнал. */
    fun encode(event: Event): String = json.encodeToString(Event.serializer(), event)

    /** Одно событие журнала. Поля опциональны — наполняются по типу события. */
    @Serializable
    data class Event(
        val ts: String,          // момент события, ISO-8601 UTC
        val event: String,       // llm.request | llm.response | llm.error
        val requestId: String,   // UUID вызова: связывает request/response/error
        // Поля события llm.request:
        val provider: String? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val systemPrompt: String? = null,
        val messages: List<Msg>? = null,
        // Поля событий llm.response / llm.error:
        val durationMs: Long? = null,
        val finishReason: String? = null,
        val text: String? = null,
        val usage: Usage? = null,
        val error: ErrorInfo? = null,
    )

    /** Сообщение диалога в журнале (только роль и контент). */
    @Serializable
    data class Msg(val role: String, val content: String)

    /** Счётчики токенов из ResponseMetaInfo Koog. */
    @Serializable
    data class Usage(val inputTokens: Int, val outputTokens: Int, val totalTokens: Int)

    /** Описание ошибки вызова LLM. */
    @Serializable
    data class ErrorInfo(val type: String, val message: String)
}
