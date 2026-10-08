package com.koog.chattool.routes

import com.koog.chattool.module
import com.koog.chattool.llm.KoogLlmGateway
import com.koog.chattool.llm.LlmLoggingGateway
import com.koog.chattool.llm.llmModelFrom
import com.koog.chattool.testutil.FakePromptExecutor
import com.koog.chattool.testutil.LlmTestLogs
import com.koog.chattool.testutil.fakeAssistant
import com.koog.chattool.testutil.testAppConfig
import io.ktor.http.contentType
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.nio.file.Path
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
        assertEquals("LLM_ERROR", error["code"]!!.jsonPrimitive.content)

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
}
