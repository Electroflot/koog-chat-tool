package com.koog.chattool.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON-схема инструмента save_chat_history в формате OpenAI tool calling.
 *
 * Как это работает: хост (агент/фреймворк) регистрирует эту схему как function tool,
 * LLM отвечает tool_calls — хост делает HTTP POST на наш эндпоинт /tools/save-chat
 * и подставляет результат в tool-сообщение. Полный контракт — docs/ARCHITECTURE.md,
 * раздел 6; соответствие схемы проверяется тестом ToolSchemaTest.
 */
object ToolSchema {

    /** Имя инструмента, как его видит LLM. */
    const val NAME = "save_chat_history"

    /**
     * Полная схема инструмента (strict mode: LLM не может добавлять поля,
     * поэтому additionalProperties = false на всех уровнях).
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", NAME)
            put(
                "description",
                "Сохраняет историю текущего диалога с пользователем в JSON-файл на сервере " +
                    "(один файл на диалог). Вызывай, когда нужно зафиксировать переписку: " +
                    "в конце диалога, по просьбе пользователя или при смене темы. " +
                    "Возвращает путь к сохранённому файлу.",
            )
            put("strict", true)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
                        put(
                            "description",
                            "Идентификатор диалога. Повторный вызов с тем же id обновляет тот же файл. " +
                                "Если не указан — сервер создаст новый файл и вернёт его id.",
                        )
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("maxLength", 500)
                        put("description", "Краткий заголовок/тема диалога (необязательно).")
                    }
                    putJsonObject("messages") {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 500)
                        put("description", "Полная история диалога в хронологическом порядке.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonArray("required") {
                                // В kotlinx-serialization 1.10 add() принимает JsonElement —
                                // строки оборачиваем в JsonPrimitive явно.
                                add(JsonPrimitive("role"))
                                add(JsonPrimitive("content"))
                            }
                            putJsonObject("properties") {
                                putJsonObject("role") {
                                    put("type", "string")
                                    putJsonArray("enum") {
                                        add(JsonPrimitive("system"))
                                        add(JsonPrimitive("user"))
                                        add(JsonPrimitive("assistant"))
                                        add(JsonPrimitive("tool"))
                                    }
                                }
                                putJsonObject("content") {
                                    put("type", "string")
                                    put("description", "Текст сообщения")
                                }
                                putJsonObject("id") {
                                    put("type", "string")
                                    put("description", "Уникальный id сообщения (для идемпотентного обновления)")
                                }
                                putJsonObject("timestamp") {
                                    put("type", "string")
                                    put("format", "date-time")
                                    put("description", "Время сообщения, ISO-8601")
                                }
                            }
                            put("additionalProperties", false)
                        }
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("messages"))
                }
                put("additionalProperties", false)
            }
        }
    }
}
