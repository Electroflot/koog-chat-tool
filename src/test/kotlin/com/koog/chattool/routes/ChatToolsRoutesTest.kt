package com.koog.chattool.routes

import com.koog.chattool.module
import com.koog.chattool.model.ChatHistory
import com.koog.chattool.testutil.FakeChatGateway
import com.koog.chattool.testutil.testAppConfig
import io.ktor.http.contentType
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * Интеграционные тесты маршрутов инструмента (POST /tools/save-chat, GET /health)
 * через Ktor testApplication. Конфигурация собирается в коде на @TempDir —
 * реальные каталоги storage/ и logs/ не трогаются. LLM-шлюз подменяется фейком
 * через gatewayFactory (эндпоинт /llm/chat здесь не используется).
 */
class ChatToolsRoutesTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    /** Каталог хранилища тестового приложения. */
    private fun storageDir(): Path = tempDir.resolve("storage")

    private fun bodyJson(text: String) = Json.parseToJsonElement(text).jsonObject

    /** Поднимает тестовое приложение и выполняет блок проверок с клиентом. */
    private fun appBuilder(
        maxRequestBodyBytes: Long = 5_242_880,
        apiKey: String = "",
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            module(
                config = testAppConfig(
                    storageDir = storageDir(),
                    loggingDir = tempDir.resolve("logs"),
                    exposeChatEndpoint = false,
                    apiKey = apiKey,
                    maxRequestBodyBytes = maxRequestBodyBytes,
                ),
                // Шлюз LLM в этих тестах не вызывается — подставляем фейк,
                // чтобы не конструировать реальный Koog-клиент.
                gatewayFactory = { cfg ->
                    com.koog.chattool.llm.LlmLoggingGateway(
                        delegate = FakeChatGateway(),
                        provider = cfg.llm.provider,
                        baseUrl = cfg.llm.baseUrl,
                        model = cfg.llm.model,
                    )
                },
            )
        }
        block()
    }

    private fun saveChatBody(id: String, messagesJson: String, title: String? = null): String = buildString {
        append("""{"id": "${id}",""")
        if (title != null) append(""""title": "$title",""")
        append(""""messages": $messagesJson}""")
    }

    @Test
    fun `POST save-chat возвращает 200 и создаёт файл в каталоге хранилища`() = appBuilder {
        val response = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(saveChatBody("dialog-42", """[{"role": "user", "content": "привет", "id": "m1"}]""", title = "тема"))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val body = bodyJson(response.bodyAsText())
        assertEquals(true, body["ok"]!!.jsonPrimitive.boolean)
        assertEquals("dialog-42", body["id"]!!.jsonPrimitive.content)
        assertEquals("dialog-42.json", body["file"]!!.jsonPrimitive.content)
        assertEquals(storageDir().resolve("dialog-42.json").toString(), body["path"]!!.jsonPrimitive.content)
        assertEquals(1, body["messageCount"]!!.jsonPrimitive.int)

        // Файл реально создан и содержит историю в JSON.
        val file = storageDir().resolve("dialog-42.json")
        assertTrue(Files.isRegularFile(file))
        val history = json.decodeFromString(ChatHistory.serializer(), Files.readString(file))
        assertEquals("dialog-42", history.id)
        assertEquals("тема", history.title)
        assertEquals("привет", history.messages.single().content)
    }

    @Test
    fun `повторный POST с тем же id сливает историю - messageCount растёт`() = appBuilder {
        // Первое сохранение: одно сообщение m1.
        client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(saveChatBody("dialog-42", """[{"role": "user", "content": "версия 1", "id": "m1"}]"""))
        }
        // Второе сохранение: m1 перезаписывается (входящее побеждает), новое дописывается.
        val second = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(
                saveChatBody(
                    "dialog-42",
                    """[{"role": "user", "content": "версия 2", "id": "m1"}, {"role": "assistant", "content": "добавлено"}]""",
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(2, bodyJson(second.bodyAsText())["messageCount"]!!.jsonPrimitive.int)

        val history = json.decodeFromString(ChatHistory.serializer(), Files.readString(storageDir().resolve("dialog-42.json")))
        assertEquals(listOf("версия 2", "добавлено"), history.messages.map { it.content })
    }

    @Test
    fun `сохранение без id генерирует id на сервере`() = appBuilder {
        val response = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages": [{"role": "user", "content": "анонимный диалог"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = bodyJson(response.bodyAsText())
        val id = body["id"]!!.jsonPrimitive.content
        assertTrue(Regex("""chat-\d{8}-\d{6}-[0-9a-f]{8}""").matches(id), "id '$id' сгенерирован сервером")
        assertTrue(Files.isRegularFile(storageDir().resolve("$id.json")))
    }

    @Test
    fun `невалидный JSON возвращает 400 INVALID_JSON`() = appBuilder {
        val response = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"id": "dialog-42", "messages": """)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = bodyJson(response.bodyAsText())["error"]!!.jsonObject
        assertEquals("INVALID_JSON", error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `неизвестная роль сообщения возвращает 400 INVALID_JSON`() = appBuilder {
        val response = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(saveChatBody("dialog-42", """[{"role": "admin", "content": "привет"}]"""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = bodyJson(response.bodyAsText())["error"]!!.jsonObject
        assertEquals("INVALID_JSON", error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ошибки валидации возвращают 400 VALIDATION_FAILED`() = appBuilder {
        // Пустой список сообщений.
        val empty = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(saveChatBody("dialog-42", "[]"))
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals("VALIDATION_FAILED", bodyJson(empty.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        // Пустой контент сообщения.
        val blank = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(saveChatBody("dialog-42", """[{"role": "user", "content": "   "}]"""))
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("VALIDATION_FAILED", bodyJson(blank.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `id с path traversal возвращает 400 VALIDATION_FAILED`() = appBuilder {
        val response = client.post("/tools/save-chat") {
            contentType(ContentType.Application.Json)
            setBody(saveChatBody("../evil", """[{"role": "user", "content": "привет"}]"""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = bodyJson(response.bodyAsText())["error"]!!.jsonObject
        assertEquals("VALIDATION_FAILED", error["code"]!!.jsonPrimitive.content)

        // Файл с опасным именем не создан.
        val files = Files.list(storageDir()).use { stream -> stream.filter { it.fileName.toString().contains("evil") }.toList() }
        assertTrue(files.isEmpty(), "файлы с опасным id не должны создаваться")
    }

    @Test
    fun `слишком большое тело возвращает 413 PAYLOAD_TOO_LARGE`() = appBuilder(maxRequestBodyBytes = 100) {
        // Контент длиннее лимита; Content-Length выставляется явно, как у обычного клиента.
        val bigContent = "a".repeat(500)
        val body = saveChatBody("dialog-42", """[{"role": "user", "content": "$bigContent"}]""")
        val response = client.post("/tools/save-chat") {
            header(HttpHeaders.ContentLength, body.toByteArray().size.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("PAYLOAD_TOO_LARGE", bodyJson(response.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET health возвращает состояние сервиса`() = appBuilder(apiKey = "sk-тестовый-ключ") {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = bodyJson(response.bodyAsText())
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        val storage = body["storage"]!!.jsonObject
        assertEquals(storageDir().toString(), storage["dir"]!!.jsonPrimitive.content)
        assertEquals(true, storage["writable"]!!.jsonPrimitive.boolean)
        val llm = body["llm"]!!.jsonObject
        assertEquals(true, llm["configured"]!!.jsonPrimitive.boolean)
        assertEquals("openai-compatible", llm["provider"]!!.jsonPrimitive.content)
        assertEquals("gpt-4.1", llm["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET health показывает configured false без API-ключа`() = appBuilder(apiKey = "") {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val llm = bodyJson(response.bodyAsText())["llm"]!!.jsonObject
        assertEquals(false, llm["configured"]!!.jsonPrimitive.boolean)
        // Ключ в ответе не раскрывается в принципе.
        assertTrue(!response.bodyAsText().contains("apiKey"))
    }
}
