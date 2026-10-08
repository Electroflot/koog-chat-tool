package com.koog.chattool.llm

/**
 * Маскирование секретов перед записью в журнал LLM-вызовов.
 *
 * Цель: даже если в тексте сообщений или ответе модели окажется ключ, токен или
 * пароль, в logs/llm-requests.log они попадут только как ***MASKED***.
 * Маскирование применяется ко ВСЕМ строковым полям события ДО сериализации.
 *
 * Сам API-ключ провайдера в события журнала не включается в принципе
 * (в LlmLoggingGateway он не передаётся) — это первый рубеж защиты.
 */
object SecretMasker {

    /** Маскирующая подстановка. */
    const val MASK = "***MASKED***"

    /** Имена ключей, значения которых маскируются в парах ключ=значение / ключ: значение. */
    private val SECRET_KEYS =
        listOf("api_key", "apikey", "api-key", "authorization", "bearer", "token", "secret", "password")

    /** key = value, key: value, key= "value" (без учёта регистра ключа). */
    private val KEY_VALUE = Regex(
        """(?i)\b(${SECRET_KEYS.joinToString("|")})\b(\s*[:=]\s*)(\"[^\"]*\"|[^\s,;]+)""",
    )

    /** JSON-пара "key": "value". */
    private val JSON_KEY_VALUE = Regex(
        """(?i)\"(${SECRET_KEYS.joinToString("|")})\"\s*:\s*\"[^\"]*\"""",
    )

    /** Явные токены вида sk-... . */
    private val SK_TOKEN = Regex("""\bsk-[A-Za-z0-9_\-]{16,}\b""")

    /** Подозрительно длинные токены без пробелов (≥ 33 символа) — типичные ключи. */
    private val LONG_TOKEN = Regex("""\b[A-Za-z0-9_\-.]{33,}\b""")

    /**
     * Маскирует секреты в строке. Идемпотентна: повторное применение не меняет результат.
     * Обычный текст без секретов возвращается без изменений.
     */
    fun mask(text: String): String {
        var masked = text
        masked = KEY_VALUE.replace(masked) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MASK" }
        masked = JSON_KEY_VALUE.replace(masked) { m -> "\"${m.groupValues[1]}\": \"$MASK\"" }
        masked = SK_TOKEN.replace(masked) { "sk-$MASK" }
        masked = LONG_TOKEN.replace(masked) { MASK }
        return masked
    }
}
