package com.koog.chattool.storage

import com.koog.chattool.model.ChatHistory
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.ChatRole
import com.koog.chattool.model.SaveChatRequest
import com.koog.chattool.model.StorageException
import com.koog.chattool.model.ValidationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir

/**
 * Юнит-тесты файлового хранилища историй диалогов (ChatHistoryStorage).
 *
 * Проверяются гарантии из docs/ARCHITECTURE.md, п. 10: создание каталога и файла,
 * имя файла из id, защита от path traversal, идемпотентное слияние по id сообщений,
 * атомарность записи (нет *.tmp после ошибок), конкурентность и поведение
 * при повреждённом файле.
 */
class ChatHistoryStorageTest {

    @TempDir
    lateinit var tempDir: Path

    /** Кодек для чтения файлов историй в проверках (формат файла задаёт хранилище). */
    private val json = Json { ignoreUnknownKeys = true }

    private fun storage(dir: Path = tempDir): ChatHistoryStorage = ChatHistoryStorage(dir)

    private fun msg(id: String?, content: String) = ChatMessage(role = ChatRole.USER, content = content, id = id)

    /** Читает сохранённую историю из файла <id>.json в тестовом каталоге. */
    private fun readHistory(id: String): ChatHistory =
        json.decodeFromString(ChatHistory.serializer(), Files.readString(tempDir.resolve("$id.json")))

    @Test
    fun `конструктор создаёт каталог, а save - файл диалога`() = runTest {
        val nested = tempDir.resolve("nested/storage")
        assertFalse(Files.exists(nested), "каталога ещё нет")

        val s = ChatHistoryStorage(nested) // конструктор создаёт каталог рекурсивно
        assertTrue(Files.isDirectory(nested))

        val saved = s.save(SaveChatRequest(id = "dialog-1", messages = listOf(msg("m1", "привет"))))
        assertEquals("dialog-1.json", saved.fileName)
        assertTrue(Files.isRegularFile(nested.resolve("dialog-1.json")))
        assertEquals(1, saved.messageCount)
    }

    @Test
    fun `имя файла строится из id диалога`() = runTest {
        val saved = storage().save(
            SaveChatRequest(id = "my-chat_42.1", title = "тема", messages = listOf(msg("m1", "текст"))),
        )
        assertEquals("my-chat_42.1.json", saved.fileName)
        // path — относительно каталога хранилища (решение ревью T8: не раскрывать абсолютные пути).
        assertEquals("my-chat_42.1.json", saved.path)
        assertTrue(Files.isRegularFile(tempDir.resolve("my-chat_42.1.json")))
    }

    @Test
    fun `без id сервер генерирует id по шаблону chat-дата-случайность`() = runTest {
        val saved = storage().save(SaveChatRequest(messages = listOf(msg(null, "анонимный диалог"))))
        assertTrue(
            Regex("""chat-\d{8}-\d{6}-[0-9a-f]{8}""").matches(saved.id),
            "сгенерированный id '$saved.id' не соответствует шаблону",
        )
        assertTrue(Files.isRegularFile(tempDir.resolve("${saved.id}.json")))
    }

    @Test
    fun `path traversal id отклоняется и файл не создаётся`() = runTest {
        val s = storage()
        // Все варианты недопустимых путей → ValidationException, ничего не пишется.
        listOf("../evil", "a/b", "a\\b", "/etc/passwd", "..", "a b", "").forEach { badId ->
            val e = assertFailsWith<ValidationException> { s.save(SaveChatRequest(id = badId, messages = listOf(msg("m1", "x")))) }
            assertEquals("VALIDATION_FAILED", e.code, "id '$badId' должен быть отклонён")
        }
        // В каталоге не появилось ни одного файла.
        val files = Files.list(tempDir).use { it.toList() }
        assertTrue(files.isEmpty(), "не должно быть файлов, но есть: $files")
    }

    @Test
    fun `upsert сливает сообщения по id - входящее побеждает, без id дописываются`() = runTest {
        val s = storage()
        // Первое сохранение: два сообщения с id.
        s.save(
            SaveChatRequest(
                id = "dialog-1",
                title = "первый заголовок",
                messages = listOf(msg("m1", "версия 1"), msg("m2", "версия 2")),
            ),
        )
        // Второе сохранение: m2 перезаписывается новым содержимым, сообщение без id дописывается.
        s.save(
            SaveChatRequest(
                id = "dialog-1",
                title = "второй заголовок",
                messages = listOf(msg("m2", "версия 2 обновлена"), msg(null, "новое без id")),
            ),
        )

        val history = readHistory("dialog-1")
        assertEquals(3, history.messages.size)
        assertEquals("версия 1", history.messages[0].content, "m1 не тронут и остался на месте")
        assertEquals("версия 2 обновлена", history.messages[1].content, "m2 перезаписан входящим")
        assertEquals("новое без id", history.messages[2].content, "сообщение без id дописано в конец")
        assertEquals("второй заголовок", history.title, "title берётся из входящего запроса")
    }

    @Test
    fun `createdAt сохраняется от первой записи, а updatedAt обновляется`() = runTest {
        val s = storage()
        s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m1", "раз"))))
        val first = readHistory("d1")

        s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m2", "два"))))
        val second = readHistory("d1")

        assertEquals(first.createdAt, second.createdAt, "createdAt не меняется")
        assertTrue(second.updatedAt >= first.updatedAt, "updatedAt обновляется")
        assertEquals(2, second.messages.size)
    }

    @Test
    fun `повреждённый файл приводит к StorageException и не перезаписывается`() = runTest {
        val s = storage()
        s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m1", "данные"))))

        // Портит существующий файл: теперь это не валидный JSON.
        Files.writeString(tempDir.resolve("d1.json"), "{битый json")

        val e = assertFailsWith<StorageException> { s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m2", "новое")))) }
        assertEquals("STORAGE_ERROR", e.code)
        // Данные не потеряны: файл остался нетронутым.
        assertEquals("{битый json", Files.readString(tempDir.resolve("d1.json")))
    }

    @Test
    fun `после ошибки в каталоге не остаётся временных файлов`() = runTest {
        val s = storage()
        s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m1", "данные"))))
        Files.writeString(tempDir.resolve("d1.json"), "{битый json")

        assertFailsWith<StorageException> { s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m2", "новое")))) }

        val leftovers = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().contains(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "после ошибки не должно быть *.tmp-файлов, но есть: $leftovers")
    }

    @Test
    fun `пустой существующий файл считается отсутствующей историей`() = runTest {
        val s = storage()
        Files.createFile(tempDir.resolve("d1.json")) // пустой файл

        val saved = s.save(SaveChatRequest(id = "d1", messages = listOf(msg("m1", "первое сообщение"))))
        assertEquals(1, saved.messageCount)
        assertEquals("первое сообщение", readHistory("d1").messages.single().content)
    }

    @Test
    fun `50 параллельных сохранений одного диалога дают валидный JSON без потерь`() = runTest {
        val s = storage()
        val total = 50

        // Все сохранения идут в ОДИН диалог одновременно (реальные потоки IO):
        // чтение-слияние-запись должны сериализоваться, и ни одно сообщение не теряется.
        coroutineScope {
            repeat(total) { i ->
                launch(Dispatchers.IO) {
                    s.save(SaveChatRequest(id = "parallel", messages = listOf(msg("m$i", "сообщение-$i"))))
                }
            }
        }

        val text = Files.readString(tempDir.resolve("parallel.json"))
        val history = json.decodeFromString(ChatHistory.serializer(), text) // файл — валидный JSON
        assertEquals(total, history.messages.size, "все сообщения сохранены")
        assertEquals(
            (0 until total).map { "сообщение-$it" }.toSet(),
            history.messages.map { it.content }.toSet(),
            "ни одно сообщение не потеряно и не задублировано",
        )
        assertEquals((0 until total).map { "m$it" }.toSet(), history.messages.mapNotNull { it.id }.toSet())
    }

    @Test
    fun `checkWritable возвращает true для доступного каталога`() {
        assertTrue(storage().checkWritable())
    }

    @Test
    fun `dirName возвращает путь каталога хранилища`() {
        assertEquals(tempDir.toString(), storage().dirName())
    }

    @Test
    fun `save возвращает сообщения в том же порядке после слияния`() = runTest {
        val s = storage()
        s.save(SaveChatRequest(id = "order", messages = listOf(msg("a", "первое"), msg("b", "второе"))))
        // Повторное сохранение тех же id в обратном порядке: порядок позиций сохраняется.
        s.save(SaveChatRequest(id = "order", messages = listOf(msg("b", "второе v2"), msg("a", "первое v2"))))
        val history = readHistory("order")
        assertEquals(listOf("первое v2", "второе v2"), history.messages.map { it.content })
    }

    @Test
    fun `title остаётся от существующей записи, если входящий без title`() = runTest {
        val s = storage()
        s.save(SaveChatRequest(id = "t1", title = "исходный заголовок", messages = listOf(msg("m1", "x"))))
        s.save(SaveChatRequest(id = "t1", title = null, messages = listOf(msg("m2", "y"))))
        assertEquals("исходный заголовок", readHistory("t1").title)
    }
}
