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
    // Конфигурация читается сразу: каталог журнала нужно выставить ДО инициализации logback.
    val config = AppConfig.fromHocon()

    // logback читает системное свойство LLM_LOG_DIR при старте (см. logback.xml),
    // поэтому свойство должно быть установлено до первого использования логгеров.
    System.setProperty("LLM_LOG_DIR", config.logging.dir.toString())

    // Порт: env PORT, иначе значение по умолчанию 8080.
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    // Запускаем встроенный Netty-сервер; wait = true — блокируем поток main,
    // пока сервер работает.
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(config) }
        .start(wait = true)
}

/**
 * Сборка приложения Ktor (структура — docs/ARCHITECTURE.md, раздел 3):
 * конфигурация → плагины → хранилище → шлюз LLM (Koog + логирование) → маршруты.
 *
 * @param config конфигурация приложения; по умолчанию читается из application.conf
 *               (HOCON) + env-переменные. Тесты передают конфигурацию явно —
 *               с каталогами @TempDir, чтобы не трогать реальные storage/ и logs/.
 * @param gatewayFactory фабрика шлюза LLM. По умолчанию — реальный Koog-шлюз
 *               с обязательным логированием ([defaultGateway]). Тесты подменяют
 *               его фейковым PromptExecutor (реальные вызовы LLM в тестах запрещены).
 */
fun Application.module(
    config: AppConfig = AppConfig.fromHocon(),
    gatewayFactory: (AppConfig) -> ChatGateway = ::defaultGateway,
) {
    // 1. Общий кодек JSON: сериализация ответов И десериализация тел в маршрутах
    //    (жёсткий лимит тела требует ручного чтения байтов, а не receive<T>()).
    val json = Json {
        ignoreUnknownKeys = true   // устойчивость к лишним полям в теле
        encodeDefaults = true      // id/createdAt/updatedAt всегда в ответах
        explicitNulls = false      // null-поля в JSON не печатаем
    }

    // 2. Плагины Ktor: ContentNegotiation (этот же кодек) и единый формат ошибок.
    install(ContentNegotiation) { json(json) }
    installErrorHandling()

    // 3. Хранилище историй: каталог (storage/chats) создаётся в конструкторе.
    val storage = ChatHistoryStorage(config.storage.dir)

    // 4. Шлюз LLM: по умолчанию Koog (ВСЕ вызовы LLM — только через него), обёрнутый
    //    в LlmLoggingGateway — обязательное JSONL-логирование запросов/ответов.
    //    Фабрика позволяет тестам встроить фейк вместо реального Koog.
    val gateway: ChatGateway = gatewayFactory(config)

    // 5. Маршруты Ktor.
    routing {
        chatToolsRoutes(
            validator = ChatValidator(config.storage),
            storage = storage,
            llmConfig = config.llm,
            maxRequestBodyBytes = config.storage.maxRequestBodyBytes,
            json = json,
        )
        llmRoutes(
            gateway = gateway,
            llmConfig = config.llm,
            maxRequestBodyBytes = config.storage.maxRequestBodyBytes,
            json = json,
        )
    }
}

/**
 * Продакшен-шлюз LLM: реальный Koog (KoogLlmGateway) + обязательное логирование
 * каждого вызова (LlmLoggingGateway). Единственный источник реальных вызовов LLM
 * в приложении; в тестах вместо него через gatewayFactory подставляется фейк.
 */
private fun defaultGateway(config: AppConfig): ChatGateway = LlmLoggingGateway(
    delegate = KoogLlmGateway(
        executor = KoogClientFactory.createExecutor(config.llm),
        model = llmModelFrom(config.llm.model),
    ),
    provider = config.llm.provider,
    baseUrl = config.llm.baseUrl,
    model = config.llm.model,
)
