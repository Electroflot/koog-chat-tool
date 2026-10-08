package com.koog.chattool.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Юнит-тесты маскирования секретов (SecretMasker).
 *
 * Секреты (API-ключи, токены, пароли) не должны попадать в журнал LLM-вызовов
 * даже транзитом — mask() применяется ко всем строковым полям событий журнала
 * ДО сериализации (см. docs/ARCHITECTURE.md, п. 9).
 */
class SecretMaskerTest {

    @Test
    fun `sk-токены маскируются`() {
        val masked = SecretMasker.mask("ключ: sk-1234567890abcdefABCDEF")
        assertEquals("ключ: sk-***MASKED***", masked)
        assertFalse(masked.contains("1234567890abcdefABCDEF"))
    }

    @Test
    fun `заголовок Authorization Bearer маскируется`() {
        val input = "Authorization: Bearer sk-abcdefghijklmnop123456"
        val masked = SecretMasker.mask(input)
        assertFalse(masked.contains("sk-abcdefghijklmnop123456"), "токен не должен попасть в лог")
        assertFalse(masked.contains("Bearer "), "значение заголовка маскируется")
        assertTrue(masked.contains(SecretMasker.MASK))
    }

    @Test
    fun `пары api_key=значение маскируются`() {
        assertEquals("api_key=***MASKED***", SecretMasker.mask("api_key=supersecretvalue"))
        assertEquals("api_key: ***MASKED***", SecretMasker.mask("api_key: \"top-secret\""))
        // Ключ ищется без учёта регистра.
        assertEquals("API_KEY=***MASKED***", SecretMasker.mask("API_KEY=abc"))
    }

    @Test
    fun `JSON-пары с ключами-секретами маскируются`() {
        val masked = SecretMasker.mask("""{"api_key": "supersecret", "model": "gpt-4.1"}""")
        assertEquals("""{"api_key": "***MASKED***", "model": "gpt-4.1"}""", masked)
    }

    @Test
    fun `подозрительно длинные токены маскируются`() {
        // Слово из 33+ символов без пробелов — типичный ключ/токен.
        val masked = SecretMasker.mask("токен abcdefghijklmnopqrstuvwxyzABCDEFG закончился")
        assertEquals("токен ***MASKED*** закончился", masked)
    }

    @Test
    fun `обычный текст без секретов не портится`() {
        val ordinary = "Привет, мир! Это обычный текст диалога без каких-либо секретов."
        assertEquals(ordinary, SecretMasker.mask(ordinary))
        assertEquals("", SecretMasker.mask(""))
    }

    @Test
    fun `маскирование идемпотентно - повторное применение не меняет результат`() {
        val inputs = listOf(
            "ключ: sk-1234567890abcdefABCDEF",
            "api_key=supersecretvalue",
            """{"api_key": "supersecret"}""",
            "обычный текст",
        )
        inputs.forEach { input ->
            val once = SecretMasker.mask(input)
            assertEquals(once, SecretMasker.mask(once), "повторное маскирование '$input' изменило результат")
        }
    }

    @Test
    fun `секрет внутри текста сообщения маскируется целиком`() {
        val input = "Не записывай мой password=hunter2 в файл"
        val masked = SecretMasker.mask(input)
        assertFalse(masked.contains("hunter2"))
        assertTrue(masked.contains(SecretMasker.MASK))
    }
}
