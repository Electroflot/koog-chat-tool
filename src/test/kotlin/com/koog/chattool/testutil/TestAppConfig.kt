package com.koog.chattool.testutil

import com.koog.chattool.config.AppConfig
import com.typesafe.config.ConfigFactory
import java.nio.file.Path

/**
 * Тестовая конфигурация приложения, собираемая в коде из HOCON-строки.
 *
 * В тестах НЕЛЬЗЯ вызывать AppConfig.fromHocon(): он читает application.conf
 * и записал бы файлы историй в реальные каталоги storage/ и logs/ рабочего
 * дерева. Вместо этого каталоги указываются на @TempDir каждого теста.
 */
fun testAppConfig(
    storageDir: Path,
    loggingDir: Path,
    exposeChatEndpoint: Boolean = false,
    apiKey: String = "",
    maxMessagesPerChat: Int = 500,
    maxMessageLength: Int = 100_000,
    maxRequestBodyBytes: Long = 5_242_880,
): AppConfig = AppConfig.from(
    ConfigFactory.parseString(
        """
        app {
          storage {
            dir = ${storageDir.toString().hoconString()}
            maxMessagesPerChat = $maxMessagesPerChat
            maxMessageLength = $maxMessageLength
            maxRequestBodyBytes = $maxRequestBodyBytes
          }
          llm {
            provider = "openai-compatible"
            baseUrl = "https://api.openai.com"
            apiKey = ${apiKey.hoconString()}
            model = "gpt-4.1"
            requestTimeoutMs = 120000
            exposeChatEndpoint = $exposeChatEndpoint
          }
          logging {
            dir = ${loggingDir.toString().hoconString()}
          }
        }
        """.trimIndent(),
    ),
)

/** Экранирование пути для HOCON-строки в двойных кавычках (защита от `\` и `"`). */
private fun String.hoconString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
