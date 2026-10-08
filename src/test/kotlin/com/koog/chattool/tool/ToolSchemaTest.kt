package com.koog.chattool.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Тест JSON-схемы инструмента save_chat_history (tool calling, OpenAI-формат).
 *
 * Схема должна сериализоваться и в точности совпадать с эталонным контрактом
 * из docs/ARCHITECTURE.md, п. 6: имя инструмента, strict-режим, enum ролей,
 * pattern идентификатора, required-поля, additionalProperties=false.
 */
class ToolSchemaTest {

    /** Эталонный контракт из docs/ARCHITECTURE.md, п. 6 (копия 1-в-1). */
    private val reference = Json.parseToJsonElement(
        """
        {
          "type": "function",
          "function": {
            "name": "save_chat_history",
            "description": "Сохраняет историю текущего диалога с пользователем в JSON-файл на сервере (один файл на диалог). Вызывай, когда нужно зафиксировать переписку: в конце диалога, по просьбе пользователя или при смене темы. Возвращает путь к сохранённому файлу.",
            "strict": true,
            "parameters": {
              "type": "object",
              "properties": {
                "id": {
                  "type": "string",
                  "pattern": "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}${'$'}",
                  "description": "Идентификатор диалога. Повторный вызов с тем же id обновляет тот же файл. Если не указан — сервер создаст новый файл и вернёт его id."
                },
                "title": {
                  "type": "string",
                  "maxLength": 500,
                  "description": "Краткий заголовок/тема диалога (необязательно)."
                },
                "messages": {
                  "type": "array",
                  "minItems": 1,
                  "maxItems": 500,
                  "description": "Полная история диалога в хронологическом порядке.",
                  "items": {
                    "type": "object",
                    "required": ["role", "content"],
                    "properties": {
                      "role": {"type": "string", "enum": ["system", "user", "assistant", "tool"]},
                      "content": {"type": "string", "description": "Текст сообщения"},
                      "id": {"type": "string", "description": "Уникальный id сообщения (для идемпотентного обновления)"},
                      "timestamp": {"type": "string", "format": "date-time", "description": "Время сообщения, ISO-8601"}
                    },
                    "additionalProperties": false
                  }
                }
              },
              "required": ["messages"],
              "additionalProperties": false
            }
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `схема в точности совпадает с эталонным контрактом`() {
        assertEquals(reference, ToolSchema.toJson(), "toJson() должен совпадать с контрактом из ARCHITECTURE.md, п. 6")
    }

    @Test
    fun `имя инструмента и тип функции заданы`() {
        val schema = ToolSchema.toJson()
        assertEquals("function", schema["type"]?.jsonPrimitive?.content)
        assertEquals(ToolSchema.NAME, schema["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `strict-режим включён на уровне function`() {
        val function = ToolSchema.toJson()["function"]!!.jsonObject
        assertTrue(function["strict"]!!.jsonPrimitive.boolean, "strict = true: LLM не может добавлять поля")
    }

    @Test
    fun `enum ролей сообщений ограничен четырьмя значениями`() {
        val items = ToolSchema.toJson()["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["messages"]!!.jsonObject["items"]!!.jsonObject
        val roles = items["properties"]!!.jsonObject["role"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "tool"), roles)
    }

    @Test
    fun `pattern id ограничивает допустимые символы - защита от path traversal`() {
        val idSchema = ToolSchema.toJson()["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["id"]!!.jsonObject
        val pattern = Regex(idSchema["pattern"]!!.jsonPrimitive.content)
        assertTrue(pattern.matches("dialog-42"))
        assertFalse(pattern.matches("../evil"), "path traversal не проходит паттерн")
        assertFalse(pattern.matches("a/b"))
    }

    @Test
    fun `required и additionalProperties заданы на всех уровнях`() {
        val parameters = ToolSchema.toJson()["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(listOf("messages"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(parameters["additionalProperties"]!!.jsonPrimitive.boolean)

        val items = parameters["properties"]!!.jsonObject["messages"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(listOf("role", "content"), items["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(items["additionalProperties"]!!.jsonPrimitive.boolean)

        val messagesSchema = parameters["properties"]!!.jsonObject["messages"]!!.jsonObject
        assertEquals(1, messagesSchema["minItems"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(500, messagesSchema["maxItems"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `сериализация схемы в JSON не падает и содержит описание`() {
        // JsonObject.toString() — корректная JSON-сериализация схемы.
        val text = ToolSchema.toJson().toString()
        assertTrue(text.contains("save_chat_history"))
        assertTrue(text.contains("description"))
    }
}
