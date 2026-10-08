package com.koog.chattool.routes

import com.koog.chattool.config.KoogConfig
import com.koog.chattool.model.InvalidJsonException
import com.koog.chattool.model.PayloadTooLargeException
import com.koog.chattool.model.SaveChatRequest
import com.koog.chattool.model.SaveChatResponse
import com.koog.chattool.storage.ChatHistoryStorage
import com.koog.chattool.validation.ChatValidator
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Маршруты инструмента для LLM (маршрутизация — Ktor):
 * - POST /tools/save-chat — сохранить историю диалога в JSON-файл;
 * - GET /health — состояние сервиса (хранилище, LLM-конфигурация).
 * Сама логика — в ChatHistoryStorage и ChatValidator; здесь только HTTP-обвязка.
 *
 * @param json кодек, общий с ContentNegotiation (см. Application.module).
 */
fun Route.chatToolsRoutes(
    validator: ChatValidator,
    storage: ChatHistoryStorage,
    llmConfig: KoogConfig,
    maxRequestBodyBytes: Long,
    json: Json,
) {
    post("/tools/save-chat") {
        // ЖЁСТКИЙ лимит тела: читаем сырые байты с капом, не полагаясь на Content-Length —
        // запросы с chunked-кодированием без заголовка обходили бы проверку (защита от OOM).
        val bodyBytes = call.readBodyWithLimit(maxRequestBodyBytes)

        // Десериализация; ошибки формата — единым кодом INVALID_JSON (400).
        val request = try {
            json.decodeFromString<SaveChatRequest>(bodyBytes.decodeToString())
        } catch (e: SerializationException) {
            // safeDetail(): причина без эха тела запроса (QA T10, дефект 3).
            throw InvalidJsonException("Тело запроса не соответствует схеме: ${e.safeDetail()}")
        }

        // Бизнес-валидация (роли, лимиты, id) — 400 VALIDATION_FAILED.
        validator.validate(request)
        // Сохранение: идемпотентный upsert с атомарной записью (см. ChatHistoryStorage).
        val saved = storage.save(request)

        call.respond(
            SaveChatResponse(
                id = saved.id,
                file = saved.fileName,
                path = saved.path,
                messageCount = saved.messageCount,
                savedAt = saved.savedAt,
            ),
        )
    }

    get("/health") {
        call.respond(
            HealthResponse(
                status = "ok",
                storage = HealthStorage(dir = storage.dirName(), writable = storage.checkWritable()),
                // API-ключ и полный URL провайдера в ответе НЕ раскрываются.
                llm = HealthLlm(
                    configured = llmConfig.isConfigured,
                    provider = llmConfig.provider,
                    model = llmConfig.model,
                ),
            ),
        )
    }
}

/**
 * Читает тело запроса потоково с жёстким байтовым лимитом: при превышении — 413
 * (PayloadTooLargeException). Не опирается на Content-Length, поэтому защита
 * работает и для chunked-тел без заголовка.
 */
suspend fun ApplicationCall.readBodyWithLimit(maxBytes: Long): ByteArray {
    val channel = receiveChannel()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        // readAvailable возвращает до buffer.size байт или -1 в конце потока.
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        total += read
        // Превышение фиксируем сразу, не дочитывая остаток тела.
        if (total > maxBytes) {
            throw PayloadTooLargeException("Тело запроса превышает лимит $maxBytes байт")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val BUFFER_SIZE = 8192

/**
 * Выжимка из сообщения kotlinx-serialization БЕЗ эха тела запроса:
 * kotlinx дописывает к ошибкам суффикс "JSON input: {...}" с самим телом —
 * возвращать клиенту его же тело не нужно (QA T10, дефект 3).
 */
fun SerializationException.safeDetail(): String =
    (message ?: "неизвестная причина")
        .substringBefore("JSON input:")
        .trim()
        .take(200)
        .ifBlank { "неизвестная причина" }

/** Ответ GET /health: состояние сервиса. */
@Serializable
private data class HealthResponse(
    val status: String,
    val storage: HealthStorage,
    val llm: HealthLlm,
)

@Serializable
private data class HealthStorage(val dir: String, val writable: Boolean)

@Serializable
private data class HealthLlm(val configured: Boolean, val provider: String, val model: String)
