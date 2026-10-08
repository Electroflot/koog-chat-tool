package com.koog.chattool.testutil

import ch.qos.logback.classic.LoggerContext
import com.koog.chattool.llm.LlmLoggingGateway
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory

/**
 * Доступ к журналу LLM-вызовов в тестах.
 *
 * Файл журнала определяется logback-test.xml (src/test/resources): каталог
 * build/test-logs, логгер "llm.requests" → файл llm-requests.log. Интеграционные
 * тесты читают этот файл и чистят его перед каждым тестом.
 */
object LlmTestLogs {

    /** Каталог журнала из logback-test.xml (относительно рабочего каталога сборки). */
    val dir: Path = Paths.get("build", "test-logs")

    /** Файл JSONL-журнала запросов/ответов LLM. */
    val file: Path = dir.resolve("llm-requests.log")

    /**
     * Очищает журнал перед тестом: удаляет файл и перезапускает файловый appender
     * логгера "llm.requests". Перезапуск обязателен — иначе appender продолжает
     * писать в удалённый дескриптор файла, и следующий тест увидит пустой журнал.
     */
    fun reset() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val llmLogger = context.getLogger(LlmLoggingGateway.LOGGER_NAME)
        llmLogger.getAppender("LLM-REQUESTS")?.stop()
        Files.deleteIfExists(file)
        llmLogger.getAppender("LLM-REQUESTS")?.start()
    }

    /** Строки журнала (одна JSON-строка на событие). */
    fun lines(): List<String> = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
}
