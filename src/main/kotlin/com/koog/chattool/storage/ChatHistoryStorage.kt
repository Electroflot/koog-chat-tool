package com.koog.chattool.storage

import com.koog.chattool.model.ChatHistory
import com.koog.chattool.model.ChatIds
import com.koog.chattool.model.ChatMessage
import com.koog.chattool.model.SaveChatRequest
import com.koog.chattool.model.StorageException
import com.koog.chattool.model.ValidationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Файловое хранилище историй диалогов: один JSON-файл на диалог.
 *
 * Гарантии:
 * - АТОМАРНОСТЬ — запись во временный файл в том же каталоге, затем атомарный move;
 *   читатель никогда не увидит частично записанный файл.
 * - КОНКУРЕНТНОСТЬ — на каждый файл свой Mutex: чтение-слияние-запись одного
 *   диалога сериализованы, разные диалоги пишутся параллельно.
 * - БЕЗОПАСНОСТЬ — имя файла строится только из валидного id (паттерн ChatIds),
 *   что исключает path traversal; повреждённый существующий файл НЕ перезаписывается
 *   молча, а приводит к ошибке (данные не теряются).
 */
class ChatHistoryStorage(
    /** Каталог, в котором лежат JSON-файлы диалогов (создаётся при старте). */
    private val baseDir: Path,

    /** Кодек JSON для (де)сериализации историй. */
    private val json: Json = Json {
        prettyPrint = true          // файлы историй читаются и человеком
        ignoreUnknownKeys = true    // устойчивость к добавлению полей в будущем
        encodeDefaults = true       // createdAt/updatedAt всегда присутствуют в файле
    },
) {
    /**
     * Блокировки на файл: ключ — имя файла, значение — Mutex.
     * Map не очищается: она растёт вместе с числом уникальных диалогов за жизнь
     * процесса, что для файлового хранилища приемлемо (см. ARCHITECTURE.md, п. 10).
     * Удалять записи опасно гонкой: ожидающий корутины мог бы «потерять» блокировку.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        // Каталог хранилища создаётся рекурсивно при старте сервиса.
        Files.createDirectories(baseDir)
        // Однократная уборка временных файлов, оставшихся после падения процесса:
        // штатный cleanup в finally не выполняется при kill -9.
        cleanupStaleTempFiles()
    }

    /** Удаляет временные файлы *.tmp-*, оставшиеся после аварийного завершения. */
    private fun cleanupStaleTempFiles() {
        try {
            Files.list(baseDir).use { entries ->
                entries
                    .filter { it.fileName.toString().contains(".tmp-") }
                    .forEach { runCatching { Files.deleteIfExists(it) } } // неудача уборки не роняет старт
            }
        } catch (e: IOException) {
            // Каталог не читается — не блокируем старт; /health покажет writable=false.
        }
    }

    /** Результат успешного сохранения. */
    data class SavedChat(
        val id: String,
        val fileName: String,
        val path: String,       // путь к файлу ОТНОСИТЕЛЬНО каталога хранилища (для ответа API)
        val messageCount: Int,
        val savedAt: Instant,
    )

    /**
     * Сохраняет диалог (идемпотентный upsert):
     * - если id передан и файл существует — сообщения сливаются по id (см. [merge]);
     * - если id передан и файла нет — создаётся новый файл;
     * - если id не передан — сервер генерирует id и возвращает его в результате.
     */
    suspend fun save(request: SaveChatRequest): SavedChat {
        val now = Clock.System.now()
        val id = resolveId(request.id)
        val fileName = "$id.json"
        val target = baseDir.resolve(fileName)

        // Блокировка именно этого файла: параллельные сохранения одного диалога
        // выполняются строго по очереди (чтение → слияние → запись — атомарная тройка).
        val mutex = locks.computeIfAbsent(fileName) { Mutex() }
        return mutex.withLock {
            val existing = readExisting(target)
            val merged = merge(existing, request, id, now)
            writeAtomic(target, merged)
            SavedChat(
                id = id,
                fileName = fileName,
                // Относительный путь: не раскрываем клиенту абсолютные пути сервера.
                path = baseDir.relativize(target).toString(),
                messageCount = merged.messages.size,
                savedAt = now,
            )
        }
    }

    /** Каталог хранилища (для GET /health). */
    fun dirName(): String = baseDir.toString()

    /** Проверка, что каталог хранилища доступен на запись (для GET /health). */
    fun checkWritable(): Boolean {
        val probe = baseDir.resolve(".write-probe-${UUID.randomUUID()}")
        return try {
            Files.write(probe, ByteArray(0))
            Files.deleteIfExists(probe)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Определяет id диалога: переданный (после проверки паттерна) или сгенерированный.
     * Повторная проверка паттерна здесь — defense in depth: даже если маршрут
     * забудет провалидировать, в имя файла не попадёт ничего опасного.
     */
    private fun resolveId(requested: String?): String {
        if (requested != null) {
            if (!ChatIds.isValid(requested)) {
                throw ValidationException("Некорректный id диалога: '$requested'")
            }
            return requested
        }
        return generateId()
    }

    /** Генерирует уникальный id вида chat-<дата/время>-<8 hex> и проверяет, что файла ещё нет. */
    private fun generateId(): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(ZoneOffset.UTC))
        repeat(5) {
            val rand = buildString(8) { repeat(8) { append("0123456789abcdef"[Random.nextInt(16)]) } }
            val candidate = "chat-$stamp-$rand"
            if (!Files.exists(baseDir.resolve("$candidate.json"))) return candidate
        }
        throw StorageException("Не удалось сгенерировать уникальный id диалога")
    }

    /**
     * Слияние историй (upsert) по id сообщений:
     * - совпадение id сообщения → входящее сообщение ПОБЕЖДАЕТ (позиция сохраняется);
     * - сообщения без id → дописываются в конец.
     * createdAt сохраняется от первой записи, updatedAt обновляется, title — от входящего запроса.
     */
    private fun merge(existing: ChatHistory?, incoming: SaveChatRequest, id: String, now: Instant): ChatHistory {
        val merged = LinkedHashMap<MergeKey, ChatMessage>()
        var anonCounter = 0

        // Сначала — уже сохранённые сообщения (сохраняем порядок и id).
        existing?.messages?.forEach { m ->
            val key = m.id?.let { MergeKey.Id(it) } ?: MergeKey.Anon(anonCounter++)
            merged[key] = m
        }
        // Затем — входящие: с тем же id перезаписывают, без id — дописываются в конец.
        incoming.messages.forEach { m ->
            val key = m.id?.let { MergeKey.Id(it) } ?: MergeKey.Anon(anonCounter++)
            merged[key] = m
        }

        return ChatHistory(
            id = id,
            title = incoming.title ?: existing?.title,
            messages = merged.values.toList(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
    }

    /**
     * Читает существующий файл диалога; null — файла нет (или он пустой).
     * Повреждённый JSON → StorageException: НЕ перезаписываем молча, чтобы не потерять данные.
     */
    private fun readExisting(path: Path): ChatHistory? {
        if (!Files.exists(path)) return null
        val text = try {
            Files.readString(path)
        } catch (e: IOException) {
            // Ошибка чтения (права, диск) — контрактный код STORAGE_ERROR, а не сырой 500.
            throw StorageException("Не удалось прочитать файл '${path.fileName}': ${e.message}", e)
        }
        if (text.isBlank()) return null // пустой файл — след неудачной записи, считаем «нет истории»
        return try {
            json.decodeFromString(ChatHistory.serializer(), text)
        } catch (e: SerializationException) {
            throw StorageException(
                "Файл диалога '${path.fileName}' повреждён и не может быть прочитан; " +
                    "перезапись заблокирована, чтобы не потерять данные",
                e,
            )
        }
    }

    /**
     * Атомарная запись: сериализация в память → временный файл в том же каталоге →
     * атомарный move на место назначения. При сбое временный файл подчищается.
     */
    private fun writeAtomic(target: Path, history: ChatHistory) {
        val tmp = baseDir.resolve("${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            val text = json.encodeToString(ChatHistory.serializer(), history)
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                // Файловая система без атомарного move — деградируем предсказуемо (обычный move).
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw StorageException("Не удалось записать файл '${target.fileName}': ${e.message}", e)
        } finally {
            // Ошибка удаления временного файла не должна маскировать исходную ошибку записи.
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /**
     * Ключ слияния сообщений: либо явный id сообщения, либо синтетический индекс
     * для сообщений без id. Синтетические индексы не могут совпасть с реальными id.
     */
    private sealed interface MergeKey {
        data class Id(val value: String) : MergeKey
        data class Anon(val index: Int) : MergeKey
    }
}
