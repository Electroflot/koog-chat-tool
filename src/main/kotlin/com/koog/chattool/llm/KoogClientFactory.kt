package com.koog.chattool.llm

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import com.koog.chattool.config.KoogConfig

/**
 * Фабрика Koog-объектов — ЕДИНСТВЕННОЕ место в проекте, где создаются
 * классы фреймворка Koog (проверяется на код-ревью: импорты ai.koog.*
 * допустимы только в пакете llm).
 *
 * Все OpenAI-совместимые провайдеры (OpenAI, DeepSeek, OpenRouter и др.)
 * обслуживаются одним клиентом OpenAILLMClient с кастомным baseUrl —
 * это подтверждено сигнатурой OpenAIClientSettings(baseUrl = ...).
 */
object KoogClientFactory {

    /**
     * Создаёт [PromptExecutor] из конфигурации приложения.
     * Executor возвращается по ИНТЕРФЕЙСУ PromptExecutor, чтобы в тестах
     * его можно было подменить рукописным фейком (реальные вызовы LLM в тестах запрещены).
     */
    fun createExecutor(cfg: KoogConfig): PromptExecutor {
        // Настройки OpenAI-совместимого клиента: адрес провайдера и таймауты
        // (порядок аргументов: request / connect / socket — из сигнатуры Koog 1.3.0).
        val settings = OpenAIClientSettings(
            baseUrl = cfg.baseUrl,
            timeoutConfig = ConnectionTimeoutConfig(
                cfg.requestTimeoutMs,  // таймаут запроса
                cfg.requestTimeoutMs,  // таймаут соединения
                cfg.requestTimeoutMs,  // таймаут сокета
            ),
        )
        // Клиент с API-ключом из конфигурации (в логи ключ НИКОГДА не попадает — см. LlmLog).
        val client = OpenAILLMClient(apiKey = cfg.apiKey, settings = settings)
        // MultiLLMPromptExecutor с одним клиентом — стандартный способ получить
        // PromptExecutor в Koog 1.3.0 (класс SingleLLMPromptExecutor в jar 1.3.0 отсутствует).
        return MultiLLMPromptExecutor(client)
    }
}
