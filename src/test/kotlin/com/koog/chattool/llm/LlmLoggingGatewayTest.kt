package com.koog.chattool.llm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.koog.chattool.model.ChatException
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.ChatRole
import com.koog.chattool.model.LlmErrorType
import com.koog.chattool.testutil.FakeChatGateway
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Юнит-тесты декоратора логирования вызовов LLM (LlmLoggingGateway).
 *
 * Каждый вызов LLM обязан порождать ровно два события журнала: llm.request перед
 * вызовом и llm.response (успех) ИЛИ llm.error (исключение), связанные общим
 * requestId (см. docs/ARCHITECTURE.md, п. 9). Записи перехватываются в память
 * через logback ListAppender — вторичный конструктор LlmLoggingGateway принимает
 * собственный Logger (файл не трогаем).
 */
class LlmLoggingGatewayTest {

    /** Логгер-заглушка с перехватом событий в память. */
    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeTest
    fun setUp() {
        // Отдельное имя логгера на каждый тест: не пересекаемся с файловым
        // логгером "llm.requests" из logback-test.xml.
        logger = LoggerFactory.getLogger("llm.requests.unit") as Logger
        logger.level = Level.INFO
        logger.isAdditive = false
        appender = ListAppender()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        logger.detachAppender(appender)
    }

    /** События журнала из перехваченных записей (каждая запись — одна JSON-строка). */
    private fun events(): List<LlmLog.Event> = appender.list.map { event ->
        Json.decodeFromString(LlmLog.Event.serializer(), event.formattedMessage)
    }

    private fun gateway(delegate: ChatGateway): LlmLoggingGateway = LlmLoggingGateway(
        delegate = delegate,
        provider = "openai-compatible",
        baseUrl = "https://api.openai.com",
        model = "gpt-4.1",
        log = logger,
    )

    private fun requestWithSecret() = ChatRequest(
        systemPrompt = "ты помощник",
        messages = listOf(ChatMessage(ChatRole.USER, "привет, мой ключ sk-1234567890abcdef")),
    )

    @Test
    fun `успешный вызов пишет ровно llm request и llm response с общим requestId`() = runTest {
        val delegate = FakeChatGateway(
            result = ChatResult(
                requestId = "id-делегата",
                text = "привет от LLM",
                finishReason = "stop",
                inputTokens = 12,
                outputTokens = 34,
                totalTokens = 46,
            ),
        )
        val result = gateway(delegate).chat(requestWithSecret())

        val events = events()
        assertEquals(2, events.size, "ровно два события: request и response")

        // Событие запроса.
        val request = events[0]
        assertEquals("llm.request", request.event)
        assertEquals("openai-compatible", request.provider)
        assertEquals("https://api.openai.com", request.baseUrl)
        assertEquals("gpt-4.1", request.model)
        assertEquals("ты помощник", request.systemPrompt)
        assertEquals(listOf("user"), request.messages?.map { it.role })
        assertTrue(request.ts.isNotBlank())

        // Событие ответа.
        val response = events[1]
        assertEquals("llm.response", response.event)
        assertEquals("привет от LLM", response.text)
        assertEquals("stop", response.finishReason)
        assertEquals(12, response.usage?.inputTokens)
        assertEquals(34, response.usage?.outputTokens)
        assertEquals(46, response.usage?.totalTokens)
        assertTrue(response.durationMs != null && response.durationMs >= 0)

        // requestId связывает оба события и совпадает с результатом.
        assertEquals(request.requestId, response.requestId)
        assertEquals(result.requestId, request.requestId)
        assertNotEquals("id-делегата", result.requestId, "requestId перезаписывается на общий с журналом")
    }

    @Test
    fun `делегат получает оригинальный запрос - маскируются только журналы`() = runTest {
        val delegate = FakeChatGateway()
        gateway(delegate).chat(requestWithSecret())

        // Делегат видит текст как есть (без маскирования).
        assertEquals("привет, мой ключ sk-1234567890abcdef", delegate.lastRequest?.messages?.single()?.content)
        assertEquals("ты помощник", delegate.lastRequest?.systemPrompt)

        // В журнале секрет замаскирован.
        val rawLines = appender.list.map { it.formattedMessage }
        assertFalse(rawLines.joinToString().contains("sk-1234567890abcdef"), "секрет не должен попасть в журнал")
        assertTrue(rawLines.joinToString().contains(SecretMasker.MASK))
    }

    @Test
    fun `исключение делегата пишет llm request и llm error и пробрасывается`() = runTest {
        val delegate = FakeChatGateway(
            failure = ChatException(LlmErrorType.LLM_TIMEOUT, "Таймаут вызова LLM: слишком долго"),
        )

        val e = assertFailsWith<ChatException> { gateway(delegate).chat(requestWithSecret()) }
        assertEquals(LlmErrorType.LLM_TIMEOUT, e.type)

        val events = events()
        assertEquals(2, events.size, "ровно два события: request и error")
        assertEquals("llm.request", events[0].event)
        assertEquals("llm.error", events[1].event)
        assertEquals(events[0].requestId, events[1].requestId, "requestId общий для пары событий")
        assertEquals("LLM_TIMEOUT", events[1].error?.type)
        assertEquals("Таймаут вызова LLM: слишком долго", events[1].error?.message)
    }

    @Test
    fun `разные вызовы получают разные requestId`() = runTest {
        val gw = gateway(FakeChatGateway())
        val first = gw.chat(requestWithSecret())
        val second = gw.chat(requestWithSecret())
        assertNotEquals(first.requestId, second.requestId)

        val events = events()
        assertEquals(4, events.size, "два вызова — четыре события")
        assertEquals(first.requestId, events[0].requestId)
        assertEquals(first.requestId, events[1].requestId)
        assertEquals(second.requestId, events[2].requestId)
        assertEquals(second.requestId, events[3].requestId)
    }
}
