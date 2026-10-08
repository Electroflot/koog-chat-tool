package com.koog.chattool.validation

import com.koog.chattool.config.AppConfig
import com.koog.chattool.model.ChatIds
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.SaveChatRequest
import com.koog.chattool.model.ValidationException

/**
 * Правила валидации истории чата (см. docs/ARCHITECTURE.md, раздел 5.1).
 * Лимиты берутся из конфигурации app.storage; нарушения → ValidationException (400).
 */
class ChatValidator(
    private val storageConfig: AppConfig.StorageConfig,
) {

    /** Полная проверка запроса на сохранение диалога. */
    fun validate(request: SaveChatRequest) {
        // id диалога определяет имя файла — только безопасный паттерн (защита от path traversal).
        request.id?.let { id ->
            if (!ChatIds.isValid(id)) {
                throw ValidationException(
                    "Некорректный id диалога: '$id'. " +
                        "Допустимы буквы, цифры, '.', '_', '-' (1..64 символа, без путей).",
                )
            }
        }

        if (request.title != null && request.title.length > MAX_TITLE_LENGTH) {
            throw ValidationException("Заголовок диалога длиннее $MAX_TITLE_LENGTH символов")
        }

        if (request.messages.isEmpty()) {
            throw ValidationException("Список сообщений пуст: требуется хотя бы одно сообщение")
        }
        if (request.messages.size > storageConfig.maxMessagesPerChat) {
            throw ValidationException(
                "Слишком много сообщений: ${request.messages.size} " +
                    "(лимит ${storageConfig.maxMessagesPerChat})",
            )
        }

        request.messages.forEach { validateMessage(it) }
    }

    /** Проверка одного сообщения: непустой контент и лимит длины. */
    private fun validateMessage(message: ChatMessage) {
        val trimmed = message.content.trim()
        if (trimmed.isEmpty()) {
            throw ValidationException("Сообщение с ролью '${message.role}' пустое")
        }
        if (trimmed.length > storageConfig.maxMessageLength) {
            throw ValidationException(
                "Сообщение длиннее лимита в ${storageConfig.maxMessageLength} символов",
            )
        }
    }

    companion object {
        /** Максимальная длина заголовка диалога. */
        const val MAX_TITLE_LENGTH = 500
    }
}
