package com.koog.chattool.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Тело запроса POST /tools/save-chat: история диалога, которую LLM
 * передаёт на сохранение. createdAt/updatedAt проставляет сервер.
 */
@Serializable
data class SaveChatRequest(
    val id: String? = null,
    val title: String? = null,
    val messages: List<ChatMessage>,
)

/**
 * Успешный ответ POST /tools/save-chat.
 */
@Serializable
data class SaveChatResponse(
    val ok: Boolean = true,
    val id: String,         // фактический id диалога (входной или сгенерированный сервером)
    val file: String,       // имя файла, напр. "dialog-42.json"
    val path: String,       // путь к файлу относительно каталога хранилища
    val messageCount: Int,  // итоговое число сообщений после слияния
    val savedAt: Instant,   // момент сохранения (ISO-8601)
)

/**
 * Единый формат ошибок API: {"error": {"code": ..., "message": ...}}
 * (формируется плагином StatusPages в routes/ErrorHandling.kt).
 */
@Serializable
data class ErrorResponse(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
)
