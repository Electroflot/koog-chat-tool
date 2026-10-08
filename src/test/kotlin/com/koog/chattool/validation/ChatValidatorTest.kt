package com.koog.chattool.validation

import com.koog.chattool.config.AppConfig
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.ChatRole
import com.koog.chattool.model.SaveChatRequest
import com.koog.chattool.model.ValidationException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.fail

/**
 * Юнит-тесты бизнес-валидации истории чата (ChatValidator).
 *
 * Лимиты берутся из конфигурации app.storage: максимум 500 сообщений и 100 000
 * символов на сообщение (см. docs/ARCHITECTURE.md, п. 5.1). Нарушения → ValidationException
 * с кодом VALIDATION_FAILED и HTTP-статусом 400.
 */
class ChatValidatorTest {

    /** Конфигурация с прод-лимитами (как в application.conf). */
    private val validator = ChatValidator(
        AppConfig.StorageConfig(
            dir = Paths.get("/tmp/неважно"),
            maxMessagesPerChat = 500,
            maxMessageLength = 100_000,
            maxRequestBodyBytes = 5_242_880,
        ),
    )

    private fun request(
        id: String? = null,
        title: String? = null,
        messages: List<ChatMessage>,
    ) = SaveChatRequest(id = id, title = title, messages = messages)

    private fun msg(role: ChatRole, content: String) = ChatMessage(role = role, content = content)

    @Test
    fun `все четыре роли сообщений проходят валидацию`() {
        validator.validate(
            request(
                messages = listOf(
                    msg(ChatRole.SYSTEM, "системный промпт"),
                    msg(ChatRole.USER, "вопрос пользователя"),
                    msg(ChatRole.ASSISTANT, "ответ ассистента"),
                    msg(ChatRole.TOOL, "результат инструмента"),
                ),
            ),
        ) // не бросает
    }

    @Test
    fun `пустой список сообщений отклоняется`() {
        val e = assertFailsWith<ValidationException> { validator.validate(request(messages = emptyList())) }
        assertEquals("VALIDATION_FAILED", e.code)
        assertEquals(400, e.httpStatus.value)
    }

    @Test
    fun `пустой или состоящий из пробелов контент отклоняется`() {
        listOf("", "   ", "\n\t ").forEach { content ->
            val e = assertFailsWith<ValidationException> {
                validator.validate(request(messages = listOf(msg(ChatRole.USER, content))))
            }
            assertEquals("VALIDATION_FAILED", e.code, "контент '$content' должен быть отклонён")
        }
    }

    @Test
    fun `сообщение на границе лимита проходит, а сверх лимита - отклоняется`() {
        // Ровно 100 000 символов — допустимо.
        validator.validate(request(messages = listOf(msg(ChatRole.USER, "a".repeat(100_000)))))
        // 100 001 символ — превышение.
        val e = assertFailsWith<ValidationException> {
            validator.validate(request(messages = listOf(msg(ChatRole.USER, "a".repeat(100_001)))))
        }
        assertEquals("VALIDATION_FAILED", e.code)
        assertTrue(e.message!!.contains("100000"), "сообщение об ошибке должно упоминать лимит")
    }

    @Test
    fun `500 сообщений проходят, а 501 - отклоняется`() {
        validator.validate(request(messages = List(500) { msg(ChatRole.USER, "сообщение $it") }))
        val e = assertFailsWith<ValidationException> {
            validator.validate(request(messages = List(501) { msg(ChatRole.USER, "сообщение $it") }))
        }
        assertEquals("VALIDATION_FAILED", e.code)
        assertTrue(e.message!!.contains("500"), "сообщение об ошибке должно упоминать лимит")
    }

    @Test
    fun `заголовок до 500 символов проходит, длиннее - отклоняется`() {
        validator.validate(request(title = "t".repeat(500), messages = listOf(msg(ChatRole.USER, "x"))))
        val e = assertFailsWith<ValidationException> {
            validator.validate(request(title = "t".repeat(501), messages = listOf(msg(ChatRole.USER, "x"))))
        }
        assertEquals("VALIDATION_FAILED", e.code)
        // null-заголовок допустим.
        validator.validate(request(title = null, messages = listOf(msg(ChatRole.USER, "x"))))
    }

    @Test
    fun `валидные id проходят проверку`() {
        listOf("dialog-42", "a", "A1._-", "chat-20261008-120000-abcdef12", "x".repeat(64)).forEach { id ->
            validator.validate(request(id = id, messages = listOf(msg(ChatRole.USER, "x"))))
        }
    }

    @Test
    fun `невалидные id отклоняются - защита от path traversal`() {
        listOf("../evil", "a/b", "a\\b", "/etc/passwd", "..", "", "a b", "a?", "a\nb", "x".repeat(65)).forEach { id ->
            val e = assertFailsWith<ValidationException> {
                validator.validate(request(id = id, messages = listOf(msg(ChatRole.USER, "x"))))
            }
            assertEquals("VALIDATION_FAILED", e.code, "id '$id' должен быть отклонён")
        }
    }

    @Test
    fun `пустое сообщение отклоняется для каждой роли с указанием роли`() {
        ChatRole.entries.forEach { role ->
            try {
                validator.validate(request(messages = listOf(msg(role, ""))))
                fail("роль $role с пустым контентом должна быть отклонена")
            } catch (e: ValidationException) {
                assertEquals("VALIDATION_FAILED", e.code)
            }
        }
    }
}
