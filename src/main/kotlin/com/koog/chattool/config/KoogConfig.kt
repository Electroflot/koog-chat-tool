package com.koog.chattool.config

import com.typesafe.config.Config

/**
 * Настройки LLM-провайдера (OpenAI-совместимый API, обслуживается Koog).
 *
 * provider — логическая метка провайдера для логов (openai-compatible | deepseek | openrouter);
 * физически все OpenAI-совместимые провайдеры обслуживаются одним клиентом Koog
 * (OpenAILLMClient) с кастомным baseUrl (см. llm/KoogClientFactory.kt).
 */
data class KoogConfig(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val requestTimeoutMs: Long,
    val exposeChatEndpoint: Boolean,
) {
    /** true, если задан API-ключ — реальные вызовы LLM возможны (поле /health). */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        /** Читает секцию `app.llm` из HOCON-конфигурации. */
        fun from(config: Config): KoogConfig = KoogConfig(
            provider = config.getString("provider"),
            baseUrl = config.getString("baseUrl"),
            apiKey = config.getString("apiKey"),
            model = config.getString("model"),
            requestTimeoutMs = config.getLong("requestTimeoutMs"),
            exposeChatEndpoint = config.getBoolean("exposeChatEndpoint"),
        )
    }
}
