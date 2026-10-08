package com.koog.chattool.testutil

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.koog.chattool.llm.ChatGateway
import com.koog.chattool.llm.ChatRequest
import com.koog.chattool.llm.ChatResult
import com.koog.chattool.model.ChatException
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Тестовые двойники для Koog и шлюза LLM.
 *
 * Реальные вызовы LLM в тестах ЗАПРЕЩЕНЫ (нет сети и API-ключей), поэтому Koog
 * подменяется рукописным фейком интерфейса PromptExecutor: он возвращает заранее
 * заданный Message.Assistant с нужным текстом и счётчиками токенов.
 * Сигнатуры классов Koog взяты из байт-кода jar-файлов версии 1.3.0:
 * - PromptExecutor — абстрактный класс; абстрактные члены: execute(prompt, model, tools),
 *   executeStreaming(prompt, model, tools) и moderate(prompt, model);
 * - Message.Assistant(text, metaInfo, finishReason, rawResponse, id) — вторичный
 *   конструктор от текста (text превращается в текстовую часть ответа);
 * - ResponseMetaInfo(timestamp, totalTokensCount, inputTokensCount, outputTokensCount,
 *   modelId, metadata) — именно в таком порядке объявлены параметры.
 */
class FakePromptExecutor(
    /** Функция-ответ фейка: получает промпт и модель, возвращает ответ ассистента. */
    private val response: suspend (Prompt, LLModel) -> Message.Assistant,
) : PromptExecutor() {

    /** Единственная точка, которую вызывает KoogLlmGateway при вызове LLM. */
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        response(prompt, model)

    /** Стриминг в тестах не используется — пустой поток. */
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        emptyFlow()

    /** Модерация в тестах не используется — «всё безопасно». */
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    /** PromptExecutorAPI наследует AutoCloseable — фейку закрывать нечего. */
    override fun close() {}
}

/**
 * Готовый ответ ассистента для FakePromptExecutor: текст, причина завершения
 * и счётчики токенов (из ResponseMetaInfo) — ровно то, что разбирает KoogLlmGateway.
 */
fun fakeAssistant(
    text: String,
    finishReason: String? = "stop",
    inputTokens: Int = 10,
    outputTokens: Int = 20,
    totalTokens: Int = 30,
): Message.Assistant = Message.Assistant(
    content = text,
    metaInfo = ResponseMetaInfo(
        timestamp = Clock.System.now(),
        totalTokensCount = totalTokens,
        inputTokensCount = inputTokens,
        outputTokensCount = outputTokens,
    ),
    finishReason = finishReason,
)

/**
 * Фейковый ChatGateway: возвращает фиксированный результат (или бросает заданное
 * исключение) и запоминает последний полученный запрос для проверок в тестах.
 */
class FakeChatGateway(
    var result: ChatResult = ChatResult(
        requestId = "fake-delegate-request-id",
        text = "фейковый ответ",
        finishReason = "stop",
        inputTokens = 1,
        outputTokens = 2,
        totalTokens = 3,
    ),
    /** Если задано — chat() бросает это исключение (имитация сбоя LLM). */
    var failure: ChatException? = null,
) : ChatGateway {

    /** Последний запрос, переданный шлюзу (для проверки делегирования). */
    var lastRequest: ChatRequest? = null
        private set

    override suspend fun chat(request: ChatRequest): ChatResult {
        lastRequest = request
        failure?.let { throw it }
        return result
    }
}
