package com.koog.chattool.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import com.koog.chattool.model.ChatException
import com.koog.chattool.model.ChatRole
import com.koog.chattool.model.LlmErrorType
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Реализация шлюза LLM на фреймворке Koog.
 *
 * Преобразует [ChatRequest] в Koog Prompt (через prompt DSL) и выполняет его
 * через PromptExecutor. Ошибки провайдера классифицируются в [LlmErrorType]
 * и выбрасываются как [ChatException] — их обрабатывает StatusPages,
 * а LlmLoggingGateway пишет событие llm.error в журнал.
 */
class KoogLlmGateway(
    /** Executor Koog — интерфейс, поэтому в тестах легко подменяется фейком. */
    private val executor: PromptExecutor,
    /** Модель, с которой разговаривает шлюз (имя из конфигурации app.llm.model). */
    private val model: LLModel,
) : ChatGateway {

    override suspend fun chat(request: ChatRequest): ChatResult {
        // UUID вызова: связывает события журнала llm.request / llm.response / llm.error.
        val requestId = UUID.randomUUID().toString()

        // Собираем Koog-промпт: системный промпт (если задан) + сообщения диалога.
        val prompt = prompt("llm-chat") {
            request.systemPrompt?.let { system(it) }
            request.messages.forEach { message ->
                when (message.role) {
                    ChatRole.SYSTEM -> system(message.content)
                    ChatRole.USER -> user(message.content)
                    // Роли assistant/tool в верификационном запросе не используются —
                    // валидатор эндпоинта /llm/chat отклонит их раньше.
                    else -> throw IllegalArgumentException(
                        "Роль '${message.role}' не поддерживается в запросе к LLM",
                    )
                }
            }
        }

        val assistant: Message.Assistant = try {
            // Единственная точка реального вызова LLM в проекте — через Koog.
            executor.execute(prompt = prompt, model = model)
        } catch (e: CancellationException) {
            throw e // отмену корутины НЕ проглатываем — пробрасываем как есть
        } catch (e: Exception) {
            throw mapKoogError(e)
        }

        // Разбираем ответ: текст, причина завершения и счётчики токенов.
        // Поля счётчиков в Koog nullable — при отсутствии считаем 0.
        return ChatResult(
            requestId = requestId,
            text = assistant.textContent(),
            finishReason = assistant.finishReason,
            inputTokens = assistant.metaInfo.inputTokensCount ?: 0,
            outputTokens = assistant.metaInfo.outputTokensCount ?: 0,
            totalTokens = assistant.metaInfo.totalTokensCount ?: 0,
        )
    }

    /**
     * Классифицирует исключение провайдера в тип ошибки LLM:
     * таймауты → LLM_TIMEOUT, сетевые проблемы → LLM_UNAVAILABLE, остальное → LLM_ERROR.
     */
    private fun mapKoogError(e: Throwable): ChatException = when (e) {
        is TimeoutCancellationException, is SocketTimeoutException ->
            ChatException(LlmErrorType.LLM_TIMEOUT, "Таймаут вызова LLM: ${e.message}", e)
        is IOException ->
            ChatException(LlmErrorType.LLM_UNAVAILABLE, "LLM-провайдер недоступен: ${e.message}", e)
        else ->
            ChatException(LlmErrorType.LLM_ERROR, "Ошибка вызова LLM: ${e.message}", e)
    }
}

/**
 * Фабрика модели для шлюза: имя модели из конфигурации, провайдер — OpenAI
 * (все OpenAI-совместимые провайдеры в Koog используют LLMProvider.OpenAI,
 * а фактический адрес задаётся baseUrl в OpenAIClientSettings).
 */
fun llmModelFrom(modelId: String): LLModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = modelId,
)
