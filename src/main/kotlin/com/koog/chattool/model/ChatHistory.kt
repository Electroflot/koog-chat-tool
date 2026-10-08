package com.koog.chattool.model

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * История диалога — именно она сохраняется в один JSON-файл
 * (один файл на диалог, каталог storage/chats).
 *
 * @property id        идентификатор диалога; определяет имя файла `<id>.json`.
 *                     Если id не передан — сервер генерирует его сам (ChatHistoryStorage).
 * @property title     необязательный заголовок/тема диалога (≤ 500 символов).
 * @property messages  сообщения диалога в хронологическом порядке (1..500).
 * @property createdAt время ПЕРВОГО сохранения — проставляет сервер и больше не меняет.
 * @property updatedAt время ПОСЛЕДНЕГО сохранения — обновляется при каждом upsert.
 */
@Serializable
data class ChatHistory(
    val id: String? = null,
    val title: String? = null,
    val messages: List<ChatMessage>,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
)
