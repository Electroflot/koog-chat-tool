package com.koog.chattool.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Роль автора сообщения в диалоге.
 * @SerialName задаёт строчное представление в JSON: "system" | "user" | "assistant" | "tool".
 */
@Serializable
enum class ChatRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
}

/**
 * Одно сообщение диалога.
 *
 * @property role      роль автора сообщения (обязательное поле).
 * @property content   текст сообщения; 1..100_000 символов после trim
 *                     (лимиты проверяет ChatValidator).
 * @property id        необязательный id сообщения. Используется при идемпотентном
 *                     слиянии диалогов: повторное сохранение с тем же id сообщения
 *                     перезаписывает старое, без id — дописывается в конец.
 * @property timestamp время сообщения в ISO-8601 (опционально).
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val id: String? = null,
    val timestamp: Instant? = null,
)
