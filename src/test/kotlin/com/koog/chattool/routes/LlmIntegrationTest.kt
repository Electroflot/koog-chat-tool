package com.koog.chattool.routes

import com.koog.chattool.module
import com.koog.chattool.llm.KoogClientFactory
import com.koog.chattool.llm.KoogLlmGateway
import com.koog.chattool.llm.LlmLoggingGateway
import com.koog.chattool.llm.llmModelFrom
import com.koog.chattool.testutil.FakePromptExecutor
import com.koog.chattool.testutil.LlmTestLogs
import com.koog.chattool.testutil.fakeAssistant
import com.koog.chattool.testutil.testAppConfig
import com.sun.net.httpserver.HttpServer
import io.ktor.http.contentType
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * Интеграционный тест эндпоинта POST /llm/chat (верификация шлюза LLM).
 *
 * Реальный Koog в тестах запрещён: в module() через gatewayFactory встраивается
 * цепочка LlmLoggingGateway → KoogLlmGateway → FakePromptExecutor (рукописный
 * фейк интерфейса PromptExecutor). Журнал вызовов пишется в build/test-logs/
 * llm-requests.log (настройка — src/test/resources/logback-test.xml) и
 * проверяется построчно.
 */
class LlmIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeTest
    fun resetJournal() {
        LlmTestLogs.reset()
    }

    /** Текст ответа фейкового LLM с секретом: в журнале он обязан быть замаскирован. */
    private val fakeText = "Ответ фейкового Koog: ключ sk-1234567890abcdefABCDEF внутри текста"

    private fun llmApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(
                config = testAppConfig(
                    storageDir = tempDir.resolve("storage"),
                    loggingDir = tempDir.resolve("logs"),
                    exposeChatEndpoint = true,
                ),
                // Полная прод-цепочка шлюза, но с фейковым executor вместо реального Koog.
                gatewayFactory = { cfg ->
                    LlmLoggingGateway(
                        delegate = KoogLlmGateway(
                            executor = FakePromptExecutor { _, _ ->
                                fakeAssistant(
                                    text = fakeText,
                                    finishReason = "stop",
                                    inputTokens = 7,
                                    outputTokens = 42,
                                    totalTokens = 49,
                                )
                            },
                            model = llmModelFrom(cfg.llm.model),
                        ),
                        provider = cfg.llm.provider,
                        baseUrl = cfg.llm.baseUrl,
                        model = cfg.llm.model,
                    )
                },
            )
        }
        block()
    }

    private fun chatBody(): String = """{"systemPrompt": "ты ассистент", "messages": [{"role": "user", "content": "привет"}]}"""

    private fun jsonLine(line: String) = Json.parseToJsonElement(line).jsonObject

    @Test
    fun `POST llm-chat возвращает ответ фейка и пишет ровно request и response в журнал`() = llmApp {
        val response = client.post("/llm/chat") {
            contentType(ContentType.Application.Json)
            setBody(chatBody())
        }
        assertEquals(HttpStatusCode.OK, response.status)

        // HTTP-ответ: текст фейка (в ответе API секреты НЕ маскируются), токены, requestId.
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(fakeText, body["text"]!!.jsonPrimitive.content)
        assertEquals("stop", body["finishReason"]!!.jsonPrimitive.content)
        assertEquals(7, body["usage"]!!.jsonObject["inputTokens"]!!.jsonPrimitive.int)
        assertEquals(42, body["usage"]!!.jsonObject["outputTokens"]!!.jsonPrimitive.int)
        assertEquals(49, body["usage"]!!.jsonObject["totalTokens"]!!.jsonPrimitive.int)
        val requestId = body["requestId"]!!.jsonPrimitive.content
        assertTrue(requestId.isNotBlank(), "requestId возвращается клиенту")

        // Журнал: ровно 2 строки — llm.request и llm.response с общим requestId.
        val lines = LlmTestLogs.lines()
        assertEquals(2, lines.size, "журнал должен содержать ровно два события, но их ${lines.size}")

        val requestEvent = jsonLine(lines[0])
        assertEquals("llm.request", requestEvent["event"]!!.jsonPrimitive.content)
        assertEquals(requestId, requestEvent["requestId"]!!.jsonPrimitive.content)
        assertEquals("openai-compatible", requestEvent["provider"]!!.jsonPrimitive.content)
        assertEquals("gpt-4.1", requestEvent["model"]!!.jsonPrimitive.content)
        assertEquals("ты ассистент", requestEvent["systemPrompt"]!!.jsonPrimitive.content)
        val loggedMessage = requestEvent["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", loggedMessage["role"]!!.jsonPrimitive.content)
        assertEquals("привет", loggedMessage["content"]!!.jsonPrimitive.content)
        // В строке llm.request — только поля события запроса (контракт из ARCHITECTURE.md, п. 9).
        assertFalse("error" in requestEvent, "в событии запроса не должно быть поля error")
        assertFalse("usage" in requestEvent, "в событии запроса не должно быть поля usage")

        val responseEvent = jsonLine(lines[1])
        assertEquals("llm.response", responseEvent["event"]!!.jsonPrimitive.content)
        assertEquals(requestId, responseEvent["requestId"]!!.jsonPrimitive.content)
        assertEquals(42, responseEvent["usage"]!!.jsonObject["outputTokens"]!!.jsonPrimitive.int)
        // В строке llm.response — только поля события ответа.
        assertFalse("provider" in responseEvent, "в событии ответа не должно быть поля provider")
        assertFalse("messages" in responseEvent, "в событии ответа не должно быть поля messages")

        // Секрет из текста ответа в журнале замаскирован.
        assertFalse(lines[1].contains("sk-1234567890abcdefABCDEF"), "секрет не должен попасть в журнал")
        assertTrue(lines[1].contains("***MASKED***"), "секрет в журнале маскируется")
    }

    @Test
    fun `ошибка шлюза отдаёт 502 и пишет llm request и llm error`() = testApplication {
        application {
            module(
                config = testAppConfig(
                    storageDir = tempDir.resolve("storage2"),
                    loggingDir = tempDir.resolve("logs2"),
                    exposeChatEndpoint = true,
                ),
                gatewayFactory = { cfg ->
                    LlmLoggingGateway(
                        delegate = KoogLlmGateway(
                            // Сбой провайдера: KoogLlmGateway классифицирует IOException как LLM_UNAVAILABLE.
                            executor = FakePromptExecutor { _, _ -> throw IOException("сеть недоступна") },
                            model = llmModelFrom(cfg.llm.model),
                        ),
                        provider = cfg.llm.provider,
                        baseUrl = cfg.llm.baseUrl,
                        model = cfg.llm.model,
                    )
                },
            )
        }

        val response = client.post("/llm/chat") {
            contentType(ContentType.Application.Json)
            setBody(chatBody())
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        // Контракт: код ошибки = типу классификации (LLM_UNAVAILABLE → 502).
        assertEquals("LLM_UNAVAILABLE", error["code"]!!.jsonPrimitive.content)

        val lines = LlmTestLogs.lines()
        assertEquals(2, lines.size, "при ошибке в журнале ровно два события: request и error")
        assertEquals("llm.request", jsonLine(lines[0])["event"]!!.jsonPrimitive.content)
        val errorEvent = jsonLine(lines[1])
        assertEquals("llm.error", errorEvent["event"]!!.jsonPrimitive.content)
        assertEquals("LLM_UNAVAILABLE", errorEvent["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(jsonLine(lines[0])["requestId"], errorEvent["requestId"], "requestId связывает оба события")
    }

    @Test
    fun `эндпоинт llm-chat недоступен, когда exposeChatEndpoint=false`() = testApplication {
        application {
            module(
                config = testAppConfig(
                    storageDir = tempDir.resolve("storage3"),
                    loggingDir = tempDir.resolve("logs3"),
                    exposeChatEndpoint = false,
                ),
                gatewayFactory = { cfg ->
                    LlmLoggingGateway(
                        delegate = com.koog.chattool.testutil.FakeChatGateway(),
                        provider = cfg.llm.provider,
                        baseUrl = cfg.llm.baseUrl,
                        model = cfg.llm.model,
                    )
                },
            )
        }
        val response = client.post("/llm/chat") {
            contentType(ContentType.Application.Json)
            setBody(chatBody())
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `реальный Koog-клиент доходит до провайдера и разбирает ответ`() {
        // Сквозная проверка реальной цепочки OpenAILLMClient → HTTP → парсинг ответа.
        // Регрессия дефекта QA T10: LLModel без capabilities падал до любого сетевого I/O,
        // поэтому «успешный вызов LLM» раньше был невозможен ни при какой конфигурации.
        // Здесь Koog обращается к локальному фейку OpenAI-совместимого API.
        val hits: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            hits += exchange.requestHeaders.getFirst("Authorization") ?: "без заголовка"
            hits += exchange.requestBody.readBytes().decodeToString()
            val body = """
                {"id":"chatcmpl-fake","object":"chat.completion","created":1,"model":"gpt-4.1",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ответ от фейкового OpenAI"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":7,"completion_tokens":42,"total_tokens":49}}
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val port = server.address.port
            testApplication {
                application {
                    module(
                        config = testAppConfig(
                            storageDir = tempDir.resolve("storage-real"),
                            loggingDir = tempDir.resolve("logs-real"),
                            exposeChatEndpoint = true,
                        ).copy(
                            // baseUrl и ключ — на локальный фейк-провайдер.
                            llm = testAppConfig(
                                storageDir = tempDir.resolve("storage-real"),
                                loggingDir = tempDir.resolve("logs-real"),
                                exposeChatEndpoint = true,
                            ).llm.copy(baseUrl = "http://127.0.0.1:$port", apiKey = "sk-test-key-123"),
                        ),
                        // Реальный Koog (без фейков) + обязательное логирование.
                        gatewayFactory = { cfg ->
                            LlmLoggingGateway(
                                delegate = KoogLlmGateway(
                                    executor = KoogClientFactory.createExecutor(cfg.llm),
                                    model = llmModelFrom(cfg.llm.model),
                                ),
                                provider = cfg.llm.provider,
                                baseUrl = cfg.llm.baseUrl,
                                model = cfg.llm.model,
                            )
                        },
                    )
                }

                val response = client.post("/llm/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(chatBody())
                }
                assertEquals(
                    HttpStatusCode.OK,
                    response.status,
                    "реальный Koog-клиент должен получить ответ провайдера: ${response.bodyAsText()}",
                )
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("ответ от фейкового OpenAI", body["text"]!!.jsonPrimitive.content)
                assertEquals(42, body["usage"]!!.jsonObject["outputTokens"]!!.jsonPrimitive.int)

                // Фейк-провайдер реально получил POST с ключом и телом запроса.
                // (Ключ ASCII: HTTP-заголовки передаются как Latin-1, кириллица искажается.)
                assertTrue(
                    hits.any { it.contains("sk-test-key-123") },
                    "ключ передан в заголовке Authorization: $hits",
                )
                assertTrue(hits.any { it.contains("привет") }, "тело запроса дошло до провайдера: $hits")

                // В журнале — полная пара request + response (успешный путь через реальный Koog).
                val lines = LlmTestLogs.lines()
                assertEquals(2, lines.size, "журнал: request и response")
                assertEquals("llm.request", jsonLine(lines[0])["event"]!!.jsonPrimitive.content)
                assertEquals("llm.response", jsonLine(lines[1])["event"]!!.jsonPrimitive.content)
                assertEquals(
                    jsonLine(lines[0])["requestId"],
                    jsonLine(lines[1])["requestId"],
                    "requestId связывает пару событий",
                )
            }
        } finally {
            server.stop(0)
        }
    }
}
