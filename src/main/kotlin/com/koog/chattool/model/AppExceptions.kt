package com.koog.chattool.model

import io.ktor.http.HttpStatusCode

/**
 * Иерархия ошибок приложения (см. docs/ARCHITECTURE.md, раздел 8).
 *
 * Маршруты НЕ ловят исключения точечно: плагин StatusPages (routes/ErrorHandling.kt)
 * перехватывает AppException и отдаёт клиенту единый JSON {"error": {code, message}},
 * а httpStatus задаёт HTTP-код ответа.
 */
sealed class AppException(
    val code: String,                 // машинный код ошибки (напр. VALIDATION_FAILED)
    override val message: String,     // человекочитаемое описание (без stacktrace и путей вне storage)
    val httpStatus: HttpStatusCode,   // HTTP-статус ответа
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Ошибка валидации входящих данных (400). */
class ValidationException(detail: String) :
    AppException("VALIDATION_FAILED", detail, HttpStatusCode.BadRequest)

/** Тело запроса не является валидным JSON (400). */
class InvalidJsonException(detail: String) :
    AppException("INVALID_JSON", detail, HttpStatusCode.BadRequest)

/** Тело запроса превышает лимит (413). */
class PayloadTooLargeException(detail: String) :
    AppException("PAYLOAD_TOO_LARGE", detail, HttpStatusCode.PayloadTooLarge)

/** Ошибка файлового хранилища: запись/чтение/повреждённый файл (500). */
class StorageException(detail: String, cause: Throwable? = null) :
    AppException("STORAGE_ERROR", detail, HttpStatusCode.InternalServerError, cause)

/**
 * Ошибка вызова LLM. Конкретизируется через [LlmErrorType]
 * (в лог LLM-вызовов пишется событие llm.error).
 */
class ChatException(
    val type: LlmErrorType,
    detail: String,
    cause: Throwable? = null,
) : AppException(
    // Код и HTTP-статус зависят от типа ошибки (контракт ARCHITECTURE.md п.5.1/п.8):
    // таймаут → 504 Gateway Timeout, прочие ошибки LLM → 502 Bad Gateway.
    code = type.name,
    detail,
    httpStatus = if (type == LlmErrorType.LLM_TIMEOUT) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway,
    cause,
)

/** Классификация ошибок вызова LLM. */
enum class LlmErrorType {
    LLM_UNAVAILABLE,  // сеть/провайдер недоступен → HTTP 502
    LLM_TIMEOUT,      // превышен таймаут запроса → HTTP 504
    LLM_ERROR,        // прочие ошибки → HTTP 502
}
