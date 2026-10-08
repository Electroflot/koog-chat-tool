package com.koog.chattool

import com.koog.chattool.config.AppConfig
import com.koog.chattool.llm.ChatGateway
import com.koog.chattool.llm.KoogClientFactory
import com.koog.chattool.llm.KoogLlmGateway
import com.koog.chattool.llm.LlmLoggingGateway
import com.koog.chattool.llm.llmModelFrom
import com.koog.chattool.routes.chatToolsRoutes
import com.koog.chattool.routes.installErrorHandling
import com.koog.chattool.routes.llmRoutes
import com.koog.chattool.storage.ChatHistoryStorage
import com.koog.chattool.validation.ChatValidator
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * Точка входа приложения koog-chat-tool.
 *
 * HTTP-сервис на Ktor (Netty), который предоставляет LLM инструмент сохранения
 * истории чата в JSON-файл (POST /tools/save-chat). Все вызовы LLM в проекте
 * выполняются только через фреймворк Koog (пакет llm), каждый запрос/ответ LLM
 * логируется в logs/llm-requests.log. Полное описание: docs/ARCHITECTURE.md.
 */
fun main() {
    // Порт: env PORT, иначе значение по умолчанию 8080.
    // (Остальная конфигурация читается в Application.module из application.conf.)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    // Запускаем встроенный Netty-сервер; wait = true — блокируем поток main,
    // пока сервер работает.
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * Сборка приложения Ktor (структура — docs/ARCHITECTURE.md, раздел 3):
 * конфигурация → плагины → хранилище → шлюз LLM (Koog + логирование) → маршруты.
 */
fun Application.module() {
    // 1. Конфигурация: application.conf (HOCON) + переопределение env-переменными.
    val config = AppConfig.fromHocon()

    // 2. Плагины Ktor: JSON-сериализация тел и единый формат ошибок.
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true   // устойчивость к лишним полям в теле
                encodeDefaults = true      // id/createdAt/updatedAt всегда в ответах
                explicitNulls = false      // null-поля в JSON не печатаем
            },
        )
    }
    installErrorHandling()

    // 3. Хранилище историй: каталог (storage/chats) создаётся в конструкторе.
    val storage = ChatHistoryStorage(config.storage.dir)

    // 4. Шлюз LLM: Koog (ВСЕ вызовы LLM — только через него), обёрнутый в
    //    LlmLoggingGateway — обязательное JSONL-логирование запросов/ответов.
    val gateway: ChatGateway = LlmLoggingGateway(
        delegate = KoogLlmGateway(
            executor = KoogClientFactory.createExecutor(config.llm),
            model = llmModelFrom(config.llm.model),
        ),
        provider = config.llm.provider,
        baseUrl = config.llm.baseUrl,
        model = config.llm.model,
    )

    // 5. Маршруты Ktor.
    routing {
        chatToolsRoutes(
            validator = ChatValidator(config.storage),
            storage = storage,
            llmConfig = config.llm,
            maxRequestBodyBytes = config.storage.maxRequestBodyBytes,
        )
        llmRoutes(gateway, config.llm)
    }
}
