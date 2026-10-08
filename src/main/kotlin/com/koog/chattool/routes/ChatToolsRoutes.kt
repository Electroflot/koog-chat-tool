package com.koog.chattool.routes

import com.koog.chattool.config.KoogConfig
import com.koog.chattool.model.InvalidJsonException
import com.koog.chattool.model.PayloadTooLargeException
import com.koog.chattool.model.SaveChatRequest
import com.koog.chattool.model.SaveChatResponse
import com.koog.chattool.storage.ChatHistoryStorage
import com.koog.chattool.validation.ChatValidator
import io.ktor.http.HttpHeaders
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * Маршруты инструмента для LLM (маршрутизация — Ktor):
 * - POST /tools/save-chat — сохранить историю диалога в JSON-файл;
 * - GET /health — состояние сервиса (хранилище, LLM-конфигурация).
 * Сама логика — в ChatHistoryStorage и ChatValidator; здесь только HTTP-обвязка.
 */
fun Route.chatToolsRoutes(
    validator: ChatValidator,
    storage: ChatHistoryStorage,
    llmConfig: KoogConfig,
    maxRequestBodyBytes: Long,
) {
    post("/tools/save-chat") {
        // Защита от слишком больших тел: если длина известна и выше лимита — 413.
        // (Тела без Content-Length ограничены косвенно лимитами сообщений и их числа.)
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > maxRequestBodyBytes) {
            throw PayloadTooLargeException("Тело запроса превышает лимит $maxRequestBodyBytes байт")
        }

        // Десериализация тела; ошибки формата приводятся к единому коду INVALID_JSON.
        val request = call.receiveValidated<SaveChatRequest>()
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
 * Приём тела запроса с приведением ошибок парсинга к единому формату API.
 * Ktor бросает BadRequestException / JsonConvertException / SerializationException —
 * мы превращаем их в InvalidJsonException, которую StatusPages отдаёт клиенту
 * как {"error": {"code": "INVALID_JSON", ...}} со статусом 400.
 */
// Bound T : Any обязателен: в Ktor 3.3.3 receive<T>() объявлен с тем же ограничением.
suspend inline fun <reified T : Any> ApplicationCall.receiveValidated(): T =
    try {
        receive<T>()
    } catch (e: BadRequestException) {
        throw InvalidJsonException("Тело запроса не является валидным JSON: ${e.message}")
    } catch (e: JsonConvertException) {
        throw InvalidJsonException("Тело запроса не является валидным JSON: ${e.message}")
    } catch (e: SerializationException) {
        throw InvalidJsonException("Тело запроса не соответствует схеме: ${e.message}")
    }

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
