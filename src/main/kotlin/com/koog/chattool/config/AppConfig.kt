package com.koog.chattool.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Корневая конфигурация приложения.
 *
 * Источник значений — src/main/resources/application.conf (HOCON);
 * переменные окружения подставляются автоматически благодаря синтаксису
 * ${?VAR} в самом файле конфигурации (см. таблицу env-переменных в ARCHITECTURE.md, п. 11).
 */
data class AppConfig(
    val storage: StorageConfig,
    val llm: KoogConfig,
    val logging: LoggingConfig,
) {
    /** Настройки файлового хранилища историй диалогов. */
    data class StorageConfig(
        val dir: Path,                  // каталог JSON-файлов диалогов
        val maxMessagesPerChat: Int,    // максимум сообщений в одном диалоге
        val maxMessageLength: Int,      // максимум символов в одном сообщении
        val maxRequestBodyBytes: Long,  // максимум размера тела запроса
    )

    /** Настройки каталога логов. */
    data class LoggingConfig(val dir: Path)

    companion object {
        /** Продакшен-путь: загрузка application.conf из classpath. */
        fun fromHocon(): AppConfig = from(ConfigFactory.load())

        /**
         * Построение конфигурации из произвольного Config.
         * Используется в тестах, где конфиг собирается в коде (на @TempDir),
         * а не читается из файла.
         */
        fun from(config: Config): AppConfig {
            val app = config.getConfig("app")
            return AppConfig(
                storage = StorageConfig(
                    dir = Paths.get(app.getConfig("storage").getString("dir")),
                    maxMessagesPerChat = app.getConfig("storage").getInt("maxMessagesPerChat"),
                    maxMessageLength = app.getConfig("storage").getInt("maxMessageLength"),
                    maxRequestBodyBytes = app.getConfig("storage").getLong("maxRequestBodyBytes"),
                ),
                llm = KoogConfig.from(app.getConfig("llm")),
                logging = LoggingConfig(dir = Paths.get(app.getConfig("logging").getString("dir"))),
            )
        }
    }
}
