package com.koog.chattool.llm

import com.koog.chattool.model.ChatException
import java.util.UUID
import kotlin.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Декоратор шлюза LLM: ОБЯЗАТЕЛЬНОЕ логирование каждого вызова.
 *
 * Перед вызовом пишет событие llm.request, после успеха — llm.response,
 * при исключении — llm.error (все строковые поля проходят SecretMasker).
 * Записи идут в logs/llm-requests.log через выделенный логгер "llm.requests"
 * (файловый appender с ротацией настроен в logback.xml).
 *
 * requestId генерируется здесь и возвращается в ChatResult — он один
 * для всех трёх событий и виден клиенту в ответе /llm/chat.
 */
class LlmLoggingGateway(
    /** Обёртываемый шлюз (KoogLlmGateway или фейк в тестах). */
    private val delegate: ChatGateway,
    /** Метаданные для журнала (ключ API сюда не попадает). */
    private val provider: String,
    private val baseUrl: String,
    private val model: String,
    private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME),
) : ChatGateway {

    override suspend fun chat(request: ChatRequest): ChatResult {
        val requestId = UUID.randomUUID().toString()
        val startedAt = Clock.System.now()

        // Событие llm.request — фиксируем, ЧТО именно ушло к LLM (секреты маскируются).
        log.info(
            LlmLog.encode(
                LlmLog.Event(
                    ts = startedAt.toString(),
                    event = EVENT_REQUEST,
                    requestId = requestId,
                    provider = provider,
                    baseUrl = baseUrl,
                    model = model,
                    systemPrompt = request.systemPrompt?.let(SecretMasker::mask),
                    messages = request.messages.map {
                        LlmLog.Msg(role = it.role.name.lowercase(), content = SecretMasker.mask(it.content))
                    },
                ),
            ),
        )

        return try {
            val result = delegate.chat(request)
            val durationMs = (Clock.System.now() - startedAt).inWholeMilliseconds

            // Событие llm.response — фиксируем ответ, токены и длительность.
            log.info(
                LlmLog.encode(
                    LlmLog.Event(
                        ts = Clock.System.now().toString(),
                        event = EVENT_RESPONSE,
                        requestId = requestId,
                        durationMs = durationMs,
                        finishReason = result.finishReason,
                        text = SecretMasker.mask(result.text),
                        usage = LlmLog.Usage(result.inputTokens, result.outputTokens, result.totalTokens),
                    ),
                ),
            )
            // requestId в результате заменяем своим: он должен совпадать с журналом.
            result.copy(requestId = requestId)
        } catch (e: ChatException) {
            val durationMs = (Clock.System.now() - startedAt).inWholeMilliseconds

            // Событие llm.error — тип ошибки и сообщение (секреты маскируются).
            log.error(
                LlmLog.encode(
                    LlmLog.Event(
                        ts = Clock.System.now().toString(),
                        event = EVENT_ERROR,
                        requestId = requestId,
                        durationMs = durationMs,
                        error = LlmLog.ErrorInfo(type = e.type.name, message = SecretMasker.mask(e.message)),
                    ),
                ),
            )
            throw e
        }
    }

    companion object {
        /** Имя логгера зарезервировано в logback.xml — только оно пишется в файл журнала. */
        const val LOGGER_NAME = "llm.requests"

        const val EVENT_REQUEST = "llm.request"
        const val EVENT_RESPONSE = "llm.response"
        const val EVENT_ERROR = "llm.error"
    }
}
