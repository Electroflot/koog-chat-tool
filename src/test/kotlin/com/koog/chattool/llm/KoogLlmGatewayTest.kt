package com.koog.chattool.llm

import com.koog.chattool.model.ChatException
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.ChatRole
import com.koog.chattool.model.LlmErrorType
import com.koog.chattool.testutil.FakePromptExecutor
import com.koog.chattool.testutil.fakeAssistant
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * Юнит-тесты классификации ошибок вызова LLM в KoogLlmGateway.
 *
 * Классификация (контракт ARCHITECTURE.md п.7.2/п.8): таймауты (классы с "Timeout"
 * в имени, включая Ktor HttpRequestTimeoutException) → LLM_TIMEOUT (504);
 * IOException → LLM_UNAVAILABLE (502); прочее → LLM_ERROR (502).
 */
class KoogLlmGatewayTest {

    /** Шлюз с фейковым executor, который бросает заданное исключение. */
    private fun gatewayWith(error: Throwable): KoogLlmGateway = KoogLlmGateway(
        executor = FakePromptExecutor(error = error) { _, _ ->
            fakeAssistant("не будет вызван")
        },
        model = llmModelFrom("gpt-4.1"),
    )

    private fun request() = ChatRequest(
        messages = listOf(ChatMessage(ChatRole.USER, "привет")),
    )

    @Test
    fun `таймаут соединения классифицируется как LLM_TIMEOUT со статусом 504`() = runTest {
        val e = assertFailsWith<ChatException> {
            gatewayWith(SocketTimeoutException("read timed out")).chat(request())
        }
        assertEquals(LlmErrorType.LLM_TIMEOUT, e.type)
        assertEquals("LLM_TIMEOUT", e.code, "код ошибки = типу (контракт ARCHITECTURE п.8)")
        assertEquals(HttpStatusCode.GatewayTimeout, e.httpStatus)
    }

    @Test
    fun `IOException классифицируется как LLM_UNAVAILABLE со статусом 502`() = runTest {
        val e = assertFailsWith<ChatException> {
            gatewayWith(IOException("сеть недоступна")).chat(request())
        }
        assertEquals(LlmErrorType.LLM_UNAVAILABLE, e.type)
        assertEquals("LLM_UNAVAILABLE", e.code)
        assertEquals(HttpStatusCode.BadGateway, e.httpStatus)
    }

    @Test
    fun `прочая ошибка классифицируется как LLM_ERROR со статусом 502`() = runTest {
        val e = assertFailsWith<ChatException> {
            gatewayWith(RuntimeException("неизвестный сбой")).chat(request())
        }
        assertEquals(LlmErrorType.LLM_ERROR, e.type)
        assertEquals(HttpStatusCode.BadGateway, e.httpStatus)
    }
}
