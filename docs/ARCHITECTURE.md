# Архитектура koog-chat-tool

Дата: 2026-10-08. Статус: утверждена (фаза 1, задача T1).

## 1. Назначение

koog-chat-tool — HTTP-сервис на Ktor (Kotlin/JVM, JDK 17+), который:
1. предоставляет инструмент для LLM: `POST /tools/save-chat` — сохранение истории чата в JSON-файл (один файл на диалог, каталог `storage/chats/`, настраивается);
2. все вызовы LLM в проекте выполняет ТОЛЬКО через фреймворк Koog (JetBrains, `ai.koog`) — через единый шлюз `LlmGateway`;
3. ОБЯЗАТЕЛЬНО логирует каждый запрос к LLM и каждый ответ (и ошибки) JSON-строками в `logs/llm-requests.log` с ротацией Logback и маскированием секретов;
4. подключается к OpenAI-совместимому API (base URL, ключ, модель — из конфигурации; работает с OpenAI, DeepSeek, OpenRouter и любыми совместимыми эндпоинтами).

## 2. Стек и версии

| Компонент | Версия | Примечание |
|---|---|---|
| JDK | 17+ | требование Koog и проекта |
| Kotlin | 2.3.10 | та же версия, на которой собран Koog 1.3.0 |
| Ktor (server, Netty) | 3.3.3 | та же версия, что в POM koog-ktor 1.3.0-beta |
| Koog `ai.koog:koog-agents` | 1.3.0 | стабильный зонтичный артефакт |
| Koog `ai.koog:koog-ktor` | 1.3.0-beta | опционально (beta-модуль) |
| kotlinx-serialization-json | 1.10.0 | как в POM Koog |
| kotlinx-coroutines | 1.10.2 | как в POM Koog |
| Logback | 1.6.5 | стабильный |
| JUnit Jupiter | 5.14.4 | тесты |
| Gradle | 8.10+ (wrapper) | Koog требует 8.0+ |

Точные координаты зависимостей — в разделе 15.

ВАЖНО (проверено): группа `ai.koog` не индексируется на search.maven.org (запрос `g:ai.koog` возвращает 0 результатов), но артефакты физически лежат в Maven Central (`repo1.maven.org/maven2/ai/koog/...`), и Gradle резолвит их обычным `mavenCentral()`. Не считать отсутствие в поисковике признаком несуществования артефакта.

## 3. Модули и пакеты

Один Gradle-модуль (проект небольшой; мультимодульность не окупается). Пакет-корень `com.koog.chattool`.

```
src/main/kotlin/com/koog/chattool/
├── Application.kt              # main(): чтение конфига, embeddedServer(Netty), установка плагинов Ktor
├── config/
│   ├── AppConfig.kt            # загрузка application.conf (HOCON) + переопределение env-переменными
│   └── KoogConfig.kt           # секция llm: provider, baseUrl, apiKey, model, таймауты
├── model/
│   ├── ChatMessage.kt          # @Serializable ChatMessage(role, content, id?, timestamp?)
│   ├── ChatHistory.kt          # @Serializable ChatHistory(id?, title?, messages, createdAt, updatedAt)
│   └── SaveChatDtos.kt         # SaveChatRequest / SaveChatResponse / SaveChatError
├── storage/
│   └── ChatHistoryStorage.kt   # запись одного JSON-файла на диалог, атомарность, блокировки, санитизация имён
├── llm/
│   ├── ChatGateway.kt          # интерфейс: suspend fun chat(request: ChatRequest): ChatResult
│   ├── KoogLlmGateway.kt       # реализация на Koog: OpenAILLMClient + MultiLLMPromptExecutor + prompt DSL
│   ├── LlmLoggingGateway.kt    # декоратор: JSONL-логирование запроса/ответа/ошибки, замер длительности, маскирование
│   ├── LlmLog.kt               # структура JSON-строки лога + SecretMasker
│   └── KoogClientFactory.kt    # создание OpenAILLMClient из конфигурации (baseUrl, таймауты)
├── tool/
│   └── ToolSchema.kt           # JSON-схема инструмента save_chat_history (формат OpenAI tool calling)
├── routes/
│   ├── ChatToolsRoutes.kt      # POST /tools/save-chat, GET /health
│   ├── LlmRoutes.kt            # POST /llm/chat (только для верификации шлюза; отключён по умолчанию)
│   └── ErrorHandling.kt        # StatusPages: единый формат ошибок
└── validation/
    └── ChatValidator.kt        # правила валидации истории чата и лимиты

src/main/resources/
├── application.conf            # конфигурация HOCON
└── logback.xml                 # консоль + logs/llm-requests.log (ротация, маскирование)

src/test/kotlin/com/koog/chattool/
├── storage/ChatHistoryStorageTest.kt
├── validation/ChatValidatorTest.kt
├── llm/SecretMaskerTest.kt
├── llm/LlmLoggingGatewayTest.kt
├── tool/ToolSchemaTest.kt
├── routes/ChatToolsRoutesTest.kt       # testApplication + @TempDir
└── routes/LlmIntegrationTest.kt        # testApplication + FakePromptExecutor (мок Koog)
```

Правила зависимости пакетов (сверху вниз): `routes` → `llm`/`storage`/`tool`/`validation`; `llm` → `config`; `storage` → `model`; никто не импортирует `ai.koog.*` кроме пакета `llm` (шлюза) — это проверяется на код-ревью (задача T8) и grep'ом `ai.koog` вне `llm/`.

## 4. Схема данных

Все модели — `@Serializable` (kotlinx-serialization).

### 4.1 ChatRole и ChatMessage

```kotlin
@Serializable
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }  // сериализуется строчными: "system"|"user"|"assistant"|"tool"

@Serializable
data class ChatMessage(
    val role: ChatRole,           // обязательное
    val content: String,          // обязательное, 1..100_000 символов (после trim)
    val id: String? = null,       // необязательный id сообщения (для идемпотентного слияния)
    val timestamp: Instant? = null // время сообщения (ISO-8601)
)
```

### 4.2 ChatHistory

```kotlin
@Serializable
data class ChatHistory(
    val id: String? = null,                  // id диалога; определяет имя файла. Паттерн: ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$
    val title: String? = null,               // необязательный заголовок (≤ 500 символов)
    val messages: List<ChatMessage>,         // 1..500 сообщений
    val createdAt: Instant = Clock.System.now(),  // проставляется сервером при первом сохранении
    val updatedAt: Instant = Clock.System.now()   // обновляется при каждом сохранении
)
```

Пример файла истории `storage/chats/dialog-42.json`:

```json
{
  "id": "dialog-42",
  "title": "Помощь с настройкой Ktor",
  "messages": [
    {"role": "user", "content": "Как поднять Ktor на Netty?", "id": "m1", "timestamp": "2026-10-08T10:00:00Z"},
    {"role": "assistant", "content": "embeddedServer(Netty, port = 8080) { ... }", "id": "m2", "timestamp": "2026-10-08T10:00:05Z"}
  ],
  "createdAt": "2026-10-08T10:00:00Z",
  "updatedAt": "2026-10-08T10:00:05Z"
}
```

## 5. HTTP API

Без авторизации (решено в task-plan.json: «Нет (по умолчанию)»).

### 5.1 POST /tools/save-chat

Запрос (`Content-Type: application/json`):

```json
{
  "id": "dialog-42",
  "title": "необязательный заголовок",
  "messages": [
    {"role": "user", "content": "текст", "id": "m1", "timestamp": "2026-10-08T10:00:00Z"}
  ]
}
```

Успешный ответ 200:

```json
{
  "ok": true,
  "file": "dialog-42.json",
  "path": "dialog-42.json",
  "messageCount": 1,
  "savedAt": "2026-10-08T10:00:01Z"
}
```

(`path` — путь к файлу ОТНОСИТЕЛЬНО каталога хранилища; абсолютные пути сервера клиенту не раскрываются.)

Семантика: идемпотентный upsert. Если файл для `id` существует — история объединяется: сообщения сливаются по `message.id` (совпадение id = входящее побеждает; сообщения без id просто дописываются), `updatedAt` обновляется, `createdAt` сохраняется. Если `id` не передан — генерируется новый файл с именем `chat-<yyyyMMdd-HHmmss>-<8 hex>.json`, и сервер возвращает фактический `id` в ответе (поле `id`).

Коды ошибок (единый формат из п. 8):

| HTTP | code | Условие |
|---|---|---|
| 400 | INVALID_JSON | тело не парсится |
| 400 | VALIDATION_FAILED | неверная роль, пустой контент, >500 сообщений, плохой id, title > 500 |
| 413 | PAYLOAD_TOO_LARGE | тело > 5 МБ или сообщение > 100 000 символов |
| 500 | STORAGE_ERROR | ошибка ввода-вывода/записи (в т.ч. нет прав на каталог) |

### 5.2 GET /health

`{"status": "ok", "storage": {"dir": "storage/chats", "writable": true}, "llm": {"configured": true, "provider": "openai-compatible", "model": "gpt-4.1"}}` — `llm.configured` = true, если задан apiKey. API-ключ и полный URL провайдера НЕ раскрываются (имя модели показывается).

### 5.3 POST /llm/chat (опционально, для верификации и QA)

Включён только при `app.llm.exposeChatEndpoint = true` (по умолчанию false; в тестах и QA-окружении включается). Единственная точка, где сервис сам вызывает LLM — через `LlmGateway` (Koog). Запрос: `{"messages": [{"role":"user","content":"..."}], "systemPrompt": "..."}`; ответ: `{"text": "...", "finishReason": "stop", "usage": {"inputTokens": 10, "outputTokens": 42, "totalTokens": 52}, "requestId": "..."}`. Каждый вызов логируется в `logs/llm-requests.log`.

## 6. JSON-схема инструмента для LLM (tool calling)

Сервис публикует контракт инструмента в формате OpenAI function/tool calling (его передаёт хосту — агенту/фреймворку, который регистрирует инструмент и вызывает наш HTTP-эндпоинт). Хранится в `tool/ToolSchema.kt` как `JsonObject` (проверяется тестом на соответствие контракту).

```json
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
          "pattern": "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$",
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
```

Как это доходит до LLM: хост регистрирует инструмент как OpenAI function tool с url `$SERVER_BASE_URL/tools/save-chat` (OpenAI-совместимые фреймворки формируют `tools: [{type: "function", function: {...}}]`); LLM отвечает `tool_calls` — хост делает HTTP POST на наш эндпоинт, получает `{"ok": true, "path": ...}` и подставляет результат в `tool`-сообщение.

Примечание для Koog-хостов: при выполнении промптов через Koog инструменты передаются в `PromptExecutor.execute(prompt, model, tools: List<ToolDescriptor>)`; наша схема совместима с генератором `OpenAICompatibleToolDescriptorSchemaGenerator` (ai.koog). Регистрация нашего HTTP-инструмента как `ToolDescriptor` внутри Koog-агента — опция потребителя, не входит в объём этого сервиса.

## 7. Интеграция Koog (проверенные факты и классы)

Все вызовы LLM — только через шлюз `LlmGateway`, построенный на Koog. Прямые HTTP-вызовы к провайдеру из кода запрещены.

### 7.1 Проверенные классы Koog 1.3.0

- `ai.koog.prompt.executor.clients.openai.OpenAILLMClient` — OpenAI-клиент. Конструктор: `OpenAILLMClient(apiKey: String, settings: OpenAIClientSettings = OpenAIClientSettings(), httpClientFactory, clock, toolsConverter)` (JVM no-factory entry point позволяет вызов с одним apiKey).
- `ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(baseUrl: String = "https://api.openai.com", timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig(), chatCompletionsPath: String = "v1/chat/completions", responsesAPIPath = "v1/responses", embeddingsPath = "v1/embeddings", moderationsPath = "v1/moderations", modelsPath = "v1/models")` — настройка OpenAI-СОВМЕСТИМОГО baseUrl и путей.
- `ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(client)` — executor (обёртка над клиентом; в jar 1.3.0 это единственный простой executor-класс; класс `SingleLLMPromptExecutor` в jar 1.3.0 отсутствует, несмотря на упоминание в доках — использовать MultiLLMPromptExecutor с одним клиентом, как в официальном quickstart).
- `ai.koog.prompt.executor.model.PromptExecutor` — базовый API (проверено по байт-коду 1.3.0):
  - `suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor> = emptyList()): Message.Assistant`;
  - `fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor> = emptyList()): Flow<StreamFrame>`;
  - `suspend fun executeMultipleChoices(...): List<Message.Assistant>`.
- `ai.koog.prompt.llm.LLModel(provider: LLMProvider, id: String, capabilities?, contextLength?, maxOutputTokens?)` — произвольная модель из конфига (не только константы `OpenAIModels.Chat.*`).
- `ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4o / GPT4_1 / GPT4oMini` — готовые константы моделей (id: `gpt-4o`, `gpt-4.1`).
- `ai.koog.prompt.dsl` — DSL: `prompt("chat") { system("..."); user("...") }`.
- `ai.koog.prompt.message.Message.Assistant` — ответ модели: `parts: List<MessagePart.ResponsePart>`, `metaInfo: ResponseMetaInfo` (`inputTokensCount`, `outputTokensCount`, `totalTokensCount`, `modelId`, `timestamp`), `finishReason: String?`, `rawResponse: JsonObject?`, метод `textContent(): String` (конкатенация текстовых частей через `\n`).
- Ktor-плагин (опционально, beta): `ai.koog.ktor.Koog`, установка `install(Koog) { llm { openAI(...) } }`; конфиг-ключи `koog.openai.apikey`/`baseUrl` в application.conf; route-хелпер `aiAgent(...)`; доступ к executor. В этом проекте НЕ используется как основной путь (см. 7.3).

### 7.2 Основной путь: ручное конструирование (рекомендовано)

```kotlin
// KoogClientFactory.kt — единственное место создания Koog-объектов.
// Провайдер из конфигурации; всё OpenAI-совместимое работает через OpenAILLMClient + кастомный baseUrl.
val settings = OpenAIClientSettings(
    baseUrl = cfg.baseUrl,                // напр. "https://api.openai.com"
    timeoutConfig = ConnectionTimeoutConfig(requestTimeout = cfg.requestTimeoutMs.milliseconds)
)
val client = OpenAILLMClient(apiKey = cfg.apiKey, settings = settings)
val executor = MultiLLMPromptExecutor(client)   // PromptExecutor — интерфейс для тестового мока
```

Таблица провайдеров (все OpenAI-совместимые, покрываются одним клиентом):

| Провайдер | baseUrl | chatCompletionsPath | model (пример) |
|---|---|---|---|
| OpenAI | https://api.openai.com | v1/chat/completions (по умолчанию) | gpt-4.1 |
| DeepSeek | https://api.deepseek.com | v1/chat/completions | deepseek-chat |
| OpenRouter | https://openrouter.ai/api | v1/chat/completions | openai/gpt-4o |

ВНИМАНИЕ: `chatCompletionsPath` по умолчанию БЕЗ ведущего слэша — итоговый URL = `baseUrl` + "/" + `chatCompletionsPath`.

Вызов и разбор ответа (внутри `KoogLlmGateway`):

```kotlin
val prompt = prompt("llm-chat") {
    request.systemPrompt?.let { system(it) }
    request.messages.forEach { m -> user(m.content) }
}
val model = LLModel(provider = LLMProvider.OpenAI, id = cfg.model)   // имя модели из конфига
val assistant: Message.Assistant = executor.execute(prompt = prompt, model = model)
val text = assistant.textContent()
val usage = assistant.metaInfo  // inputTokensCount / outputTokensCount / totalTokensCount
val finishReason = assistant.finishReason
```

Ошибки от Koog (исключения при сетевых сбоях/таймаутах/HTTP-статусах провайдера) перехватываются в шлюзе и превращаются в `ChatException` с кодом `LLM_UNAVAILABLE` (502) / `LLM_TIMEOUT` (504) / `LLM_ERROR` (502) — см. п. 8.

### 7.3 Почему основной путь — ручное конструирование, а не koog-ktor

- `koog-ktor` — beta-модуль (1.3.0-beta), API может меняться; наш объём использования Koog — один executor и один клиент;
- ручной путь на 100% покрывается моком интерфейса `PromptExecutor` в тестах без подъёма плагина;
- полный контроль над OpenAI-совместимым baseUrl из нашего конфига.
- Зависимость `ai.koog:koog-ktor` добавляется как optional (закомментированная строка) на случай будущего перехода на `install(Koog) {}`.

## 8. Обработка ошибок

Внутренняя иерархия: `AppException(code, message, httpStatus, cause)` → подклассы `ValidationException`, `StorageException`, `ChatException(type: LlmErrorType)`. В маршрутах исключения НЕ ловятся точечно — плагин `StatusPages` в `routes/ErrorHandling.kt` маппит всё в единый JSON:

```json
{"error": {"code": "STORAGE_ERROR", "message": "Не удалось записать файл: <причина>"}}
```

Правила: не отдавать наружу stacktrace и пути вне `app.storage.dir`; не логировать секреты (п. 9); 500/503 логировать с WARN/ERROR в основной лог (консоль), LLM-ошибки — дополнительно в `llm-requests.log` (событие `llm.error`).

## 9. Логирование запросов/ответов LLM (обязательное требование)

Каждый вызов LLM через `LlmGateway` проходит через декоратор `LlmLoggingGateway`, который пишет JSON-строки (JSONL) в `logs/llm-requests.log` через выделенный логгер `llm.requests` (logger name зарезервирован в logback.xml).

Структура строк (по одному объекту на строку; ключи и порядок фиксированы тестом):

Событие `llm.request` (перед вызовом):
```json
{"ts":"2026-10-08T10:00:00.000Z","event":"llm.request","requestId":"8f3c...","provider":"openai-compatible","baseUrl":"https://api.openai.com","model":"gpt-4.1","systemPrompt":"...","messages":[{"role":"user","content":"..."}]}
```

Событие `llm.response` (после успешного вызова):
```json
{"ts":"2026-10-08T10:00:02.000Z","event":"llm.response","requestId":"8f3c...","durationMs":2000,"finishReason":"stop","text":"ответ модели","usage":{"inputTokens":10,"outputTokens":42,"totalTokens":52}}
```

Событие `llm.error` (при исключении):
```json
{"ts":"2026-10-08T10:00:01.000Z","event":"llm.error","requestId":"8f3c...","durationMs":1000,"error":{"type":"LLM_TIMEOUT","message":"..."}}
```

Поля: `ts` — ISO-8601 UTC; `requestId` — UUID на один вызов (связывает request/response/error); `provider`, `baseUrl` (без query и userinfo), `model`; `messages` — только role+content; `text` — ответ; `usage` — из `ResponseMetaInfo`; `durationMs` — Long.

Маскирование секретов (`llm/SecretMasker.kt`, чистые функции + юнит-тесты):
- `apiKey` НИКОГДА не попадает в лог (он не включается в структуру события вообще);
- к каждому строковому полю применяется `mask()`: заменяет (а) вхождения вида `key=value`, `"key":"value"`, `key: value` для ключей из чёрного списка (`api_key`, `apikey`, `authorization`, `bearer`, `token`, `secret`, `password`), (б) токены длиной > 32 символов без пробелов, похожие на ключи (`sk-...`, `Bearer ...`) → `***MASKED***`;
- маскирование применяется ДО сериализации в JSON — секреты из контента сообщений не попадут в файл даже транзитом.

logback.xml (ротация и разделение потоков):
- консольный appender для общего лога (pattern, INFO);
- файловый `RollingFileAppender` «LLM-REQUESTS» привязан ТОЛЬКО к логгеру `llm.requests` (`additivity=false`, `immediateFlush=true`), файл `logs/llm-requests.log`, `SizeAndTimeBasedRollingPolicy`: `logs/llm-requests.%d{yyyy-MM-dd}.%i.log.gz`, `maxFileSize=10MB`, `maxHistory=30`, `totalSizeCap=1GB`;
- encoder: `%msg%n` (сообщение — уже готовый JSON, без лишнего паттерна);
- опционально `AsyncAppender` (queueSize=1024, discardingThreshold=0), чтобы запись лога не блокировала ответ.

## 10. Конкурентность при записи файлов

- Один файл = один диалог; имя файла определяется ТОЛЬКО сервером: `sanitize(id).json` (id клиента проходит `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, иначе файл называется по автосгенерированному id) — защита от path traversal (../, абсолютные пути, NUL).
- Атомарность записи: сериализация в память → запись во временный файл `<target>.tmp-<rand>` в том же каталоге → `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` (fallback на обычный move, если ФС не поддерживает атомарный — поведение деградирует предсказуемо). Читатель никогда не увидит частично записанный файл.
- Блокировки: `ConcurrentHashMap<String, Mutex>` с ключом = имя файла; чтение-слияние-запись одного диалога — под `mutex.withLock`; разные диалоги пишутся параллельно. Map растёт вместе с числом уникальных диалогов за жизнь процесса; при необходимости — `remove` после записи при отсутствии ожидающих (оптимизация, не обязательная).
- Слияние (upsert): существующий файл читается, сообщения объединяются по `id` (incoming побеждает), без-id дописываются в конец; некорректный существующий файл (битый JSON) — не молча перезаписывать: попытка сохранения завершается `STORAGE_ERROR` с пояснением (логируется), чтобы не терять данные.
- Каталог `app.storage.dir` создаётся рекурсивно при старте (`Files.createDirectories`) и проверяется на записываемость (health-check).

## 11. Конфигурация

HOCON `application.conf` (типовые значения; переменные окружения переопределяют):

```hocon
ktor {
  deployment {
    port = 8080
    port = ${?PORT}
    host = "0.0.0.0"
  }
}
app {
  storage {
    dir = "storage/chats"
    dir = ${?CHAT_STORAGE_DIR}
    maxMessagesPerChat = 500
    maxMessageLength = 100000       # символов
    maxRequestBodyBytes = 5242880   # 5 МБ
  }
  llm {
    provider = "openai-compatible"  # openai-compatible | deepseek | openrouter (все через OpenAI-совместимый клиент Koog)
    baseUrl = "https://api.openai.com"
    baseUrl = ${?LLM_BASE_URL}
    apiKey = ""
    apiKey = ${?LLM_API_KEY}
    model = "gpt-4.1"
    model = ${?LLM_MODEL}
    requestTimeoutMs = 120000
    requestTimeoutMs = ${?LLM_REQUEST_TIMEOUT_MS}
    exposeChatEndpoint = false      # POST /llm/chat — только для верификации/QA
    exposeChatEndpoint = ${?EXPOSE_LLM_CHAT}
  }
  logging {
    dir = "logs"
    dir = ${?LOG_DIR}
  }
}
```

| Env | Назначение |
|---|---|
| PORT | порт (default 8080) |
| CHAT_STORAGE_DIR | каталог историй (default storage/chats) |
| LLM_BASE_URL | base URL провайдера |
| LLM_API_KEY | ключ (обязателен для реальных вызовов LLM) |
| LLM_MODEL | имя модели |
| LLM_REQUEST_TIMEOUT_MS | таймаут запроса к LLM |
| EXPOSE_LLM_CHAT | true/false — включить POST /llm/chat |
| LOG_DIR | каталог логов (default logs) |

Провайдеры: `provider` выбирает имя в логах и `LLMProvider` для `LLModel`; физически всё работает через `OpenAILLMClient` с кастомным baseUrl (проверено: `OpenAIClientSettings.baseUrl` поддерживается). Дефолты baseUrl для deepseek: `https://api.deepseek.com`, openrouter: `https://openrouter.ai/api` (в конфиге можно задать любой).

## 12. Стратегия тестирования

Принцип: LLM в тестах НЕ вызывается никогда (нет сети, нет ключей). Koog мокается на уровне интерфейса `PromptExecutor` рукописным фейком (не mock-библиотекой — интерфейс маленький и стабильный).

Юнит-тесты (JUnit 5 + kotlin.test, `@TempDir`, `runTest`):
1. `ChatHistoryStorageTest`: создание каталога и файла; имя файла из id; санитизация `../evil` → отказ; upsert-слияние по id сообщений; атомарность (после ошибки нет `*.tmp`); конкурентность — 50 параллельных сохранений одного диалога дают валидный JSON без потерь; битый существующий файл → StorageException.
2. `ChatValidatorTest`: роли, лимиты (500 сообщений, 100k символов), пустой content, длина title.
3. `SecretMaskerTest`: `sk-...`, `Authorization: Bearer ...`, `api_key=...`, обычный текст не портится.
4. `LlmLoggingGatewayTest`: фейк-executor + перехват записей (логгер-заглушка/память) — ровно request+response; при исключении — request+error; requestId совпадает.
5. `ToolSchemaTest`: схема сериализуется и совпадает с эталонным контрактом из п. 6 (роли, required, pattern).

Интеграционные (Ktor `testApplication` + `@TempDir` для storage и logs; конфиг собирается в коде, не из файла):
6. `ChatToolsRoutesTest`: POST /tools/save-chat — 200 и файл создан; валидация → 400 VALIDATION_FAILED; path traversal id → 400; повторный POST с тем же id → слияние; GET /health → 200.
7. `LlmIntegrationTest` (exposeChatEndpoint=true, FakePromptExecutor возвращает `Message.Assistant` с `ResponseMetaInfo`): POST /llm/chat → 200 с текстом фейка; в `logs/llm-requests.log` (logback-test.xml указывает на @TempDir) ровно 2 строки JSON — `llm.request` и `llm.response`; секрет в тексте ответа замаскирован.

Запуск: `./gradlew test`. CI гоняет то же самое.

## 13. GitHub Actions CI

Репозиторий PRIVATE (`gh repo create koog-chat-tool --private`). Workflow `.github/workflows/ci.yml`:

- Триггеры: `push` в `main`, `pull_request` в `main`; `workflow_dispatch`.
- Один job `build` на `ubuntu-latest`, `timeout-minutes: 15`, `permissions: contents: read`.
- Шаги:
  1. `actions/checkout@v5`;
  2. `gradle/actions/wrapper-validation@v4` (проверка целостности wrapper);
  3. `actions/setup-java@v4` с `distribution: temurin`, `java-version: 17` (инструментарий проекта тоже 17);
  4. `gradle/actions/setup-gradle@v4` — кэширование: включён `gradle-home-cache` (зависимости и build-cache), `cache-write-only: false`; кэш ключуется по хэшу gradle-файлов автоматически;
  5. `./gradlew build test --no-daemon --stacktrace`;
  6. при падении — `actions/upload-artifact@v4` отчётов `build/reports/tests/**` и логов тестов.
- Секреты в CI НЕ нужны: тесты используют фейк-executor, реальных вызовов LLM нет; ключ LLM_API_KEY никогда не попадает в репозиторий/CI.
- Отдельный job `test-jdk21` (опционально): та же матрица на Java 21 для проверки совместимости «17+» (target остаётся 17).

## 14. Безопасность (кратко)

- Без авторизации по решению из task-plan.json (добавить позже — точка расширения: Ktor-плагин Authentication перед маршрутами).
- Path traversal: имена файлов только из санитизированного id (regex), без клиентских путей.
- Секреты: ключ только из env/конфига, в логах маскирование, в ответах API ключ не возвращается.
- Лимиты: размер тела 5 МБ, 500 сообщений, 100 000 символов/сообщение — защита от OOM.
- Логи не содержат заголовков HTTP и куки.

## 15. Gradle-координаты (проверены на 2026-10-08)

```kotlin
// build.gradle.kts (фрагмент)
plugins {
    kotlin("jvm") version "2.3.10"                  // Kotlin 2.3.10 — версия сборки Koog 1.3.0 (stable 2.4.21 — допустимая альтернатива)
    kotlin("plugin.serialization") version "2.3.10"
    application
}

val koogVersion = "1.3.0"
val ktorVersion = "3.3.3"                            // выровнено с POM koog-ktor 1.3.0-beta (последний Ktor 3.6.0 — альтернатива)

dependencies {
    // Koog — ВСЕ вызовы LLM только через него
    implementation("ai.koog:koog-agents:$koogVersion")            // стабильный зонтик: prompt-model, prompt-llm, prompt-executor-*, openai-client, agents-core
    // implementation("ai.koog:koog-ktor:1.3.0-beta")             // ОПЦИОНАЛЬНО (beta): install(Koog){} — не используется в основном пути
    // Провайдер-специфичные (опционально): "ai.koog:prompt-executor-deepseek-client:1.3.0-beta", "ai.koog:prompt-executor-openrouter-client:1.3.0"

    // Ktor server (Netty)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Сериализация и конфигурация
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.typesafe:config:1.4.3")

    // Логирование
    implementation("ch.qos.logback:logback-classic:1.6.5")

    // Тесты
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
```

Примечания по версиям: группа `ai.koog` не ищется через search.maven.org (индекс пуст), но артефакты есть в Maven Central и резолвятся `mavenCentral()`. Проверять наличие/версии — по `https://repo1.maven.org/maven2/ai/koog/<artifact>/maven-metadata.xml`. Актуальные стабильные на 2026-10-08: koog-agents 1.3.0; koog-ktor 1.3.0-beta (beta-линия — стабильной нет); Kotlin 2.4.21; Ktor 3.6.0; kotlinx-serialization-json 1.11.0 (1.12.0-RC — не брать); Logback 1.6.5.
