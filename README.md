# koog-chat-tool

HTTP-инструмент для LLM: сохраняет историю чата в JSON-файл (один файл на диалог). Сервис на Kotlin/JVM (JDK 17+) и Ktor 3.3.3 (Netty); все вызовы LLM в проекте выполняются только через фреймворк [Koog](https://github.com/JetBrains/koog) (`ai.koog:koog-agents:1.3.0`), а каждый запрос к LLM и ответ на него **обязательно** логируются в `logs/llm-requests.log` (JSONL, с ротацией и маскированием секретов).

## Возможности

- `POST /tools/save-chat` — сохранение диалога в JSON-файл; идемпотентный upsert: при повторном сохранении с тем же `id` сообщения сливаются по `id` сообщений.
- Готовый контракт инструмента `save_chat_history` в формате OpenAI tool calling (strict mode) — его можно зарегистрировать любому хосту-агенту.
- Единый шлюз LLM на Koog: OpenAI-совместимые провайдеры (OpenAI / DeepSeek / OpenRouter и другие) настраиваются через `baseUrl`, ключ и модель.
- Обязательный журнал вызовов LLM: события `llm.request` / `llm.response` / `llm.error` JSON-строками в `logs/llm-requests.log`, ротация Logback, секреты маскируются.
- Атомарная запись файлов (временный файл + атомарный move) и блокировки на диалог — параллельные сохранения одного диалога не теряют сообщений.
- Защита от path traversal (имя файла — только из валидированного `id`) и лимиты размера тела/сообщений от OOM.
- `GET /health` — состояние хранилища и конфигурации LLM без раскрытия API-ключа.
- Верификационный `POST /llm/chat` (по умолчанию отключён) — единственная точка, где сервис сам вызывает LLM.

## Быстрый старт

### Требования

- JDK 17+ (если JDK 17 на машине нет, Gradle скачает его автоматически — в `settings.gradle.kts` подключён foojay-resolver).
- Всё остальное — через Gradle wrapper (`./gradlew`), установка Gradle не требуется.

### Запуск

```bash
./gradlew run
```

Сервер поднимается на `http://localhost:8080`. Альтернатива — дистрибутив:

```bash
./gradlew installDist
build/install/koog-chat-tool/bin/koog-chat-tool
```

### Проверка

```bash
curl -X POST http://localhost:8080/tools/save-chat \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "demo-chat",
    "title": "Знакомство",
    "messages": [
      {"role": "user", "content": "Привет!", "id": "m1"},
      {"role": "assistant", "content": "Здравствуйте!", "id": "m2"}
    ]
  }'
```

Ответ 200:

```json
{"ok":true,"id":"demo-chat","file":"demo-chat.json","path":"demo-chat.json","messageCount":2,"savedAt":"2026-10-08T21:11:07.625713Z"}
```

Файл `storage/chats/demo-chat.json` создан. Для реальных вызовов LLM (эндпоинт `/llm/chat`) дополнительно нужен ключ провайдера — см. раздел «Конфигурация».

## Конфигурация

Все значения — в `src/main/resources/application.conf` (HOCON); каждая переменная окружения переопределяет значение по умолчанию через `${?VAR}`.

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `PORT` | `8080` | порт HTTP-сервера |
| `CHAT_STORAGE_DIR` | `storage/chats` | каталог JSON-файлов историй диалогов |
| `LLM_BASE_URL` | `https://api.openai.com` | base URL OpenAI-совместимого провайдера |
| `LLM_API_KEY` | пусто | ключ провайдера; обязателен для реальных вызовов LLM |
| `LLM_MODEL` | `gpt-4.1` | имя модели (например, `deepseek-chat`) |
| `LLM_REQUEST_TIMEOUT_MS` | `120000` | таймаут запроса к LLM (request/connect/socket) |
| `EXPOSE_LLM_CHAT` | `false` | `true` — включить верификационный `POST /llm/chat` |
| `LOG_DIR` | `logs` | каталог журналов (сюда пишется `llm-requests.log`) |

Пример запуска с провайдером DeepSeek:

```bash
PORT=9090 LLM_BASE_URL=https://api.deepseek.com LLM_API_KEY=sk-... LLM_MODEL=deepseek-chat ./gradlew run
```

Прочее:

- Лимиты хранилища (`maxMessagesPerChat = 500`, `maxMessageLength = 100000`, `maxRequestBodyBytes = 5242880`) задаются только в `application.conf` — env-переменных для них нет.
- Каталог журнала вызовов LLM управляется системным свойством `LLM_LOG_DIR`. Приложение выставляет его **автоматически** при старте из `app.logging.dir` (env `LOG_DIR`) до инициализации logback — вручную задавать не нужно.
- `provider` в конфигурации (`openai-compatible` / `deepseek` / `openrouter`) — логическая метка для журнала; физически все OpenAI-совместимые провайдеры обслуживаются одним клиентом Koog с кастомным `baseUrl`.

## HTTP API

### POST /tools/save-chat

Тело (`Content-Type: application/json`):

```json
{
  "id": "dialog-42",
  "title": "Помощь с настройкой Ktor",
  "messages": [
    {"role": "user", "content": "Как поднять Ktor?", "id": "m1", "timestamp": "2026-10-08T10:00:00Z"}
  ]
}
```

| Поле | Тип | Обязательное | Примечание |
|---|---|---|---|
| `id` | string | нет | id диалога, определяет имя файла. Паттерн `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |
| `title` | string | нет | заголовок диалога, до 500 символов |
| `messages[].role` | string | да | `system` \| `user` \| `assistant` \| `tool` |
| `messages[].content` | string | да | 1..100 000 символов (после trim) |
| `messages[].id` | string | нет | id сообщения — для идемпотентного слияния |
| `messages[].timestamp` | string | нет | время сообщения, ISO-8601 |

Успешный ответ 200:

```json
{"ok":true,"id":"dialog-42","file":"dialog-42.json","path":"dialog-42.json","messageCount":1,"savedAt":"2026-10-08T10:00:01Z"}
```

`path` — путь относительно каталога хранилища; абсолютные пути сервера клиенту не раскрываются. `messageCount` — итоговое число сообщений после слияния.

Семантика — идемпотентный **upsert**:

- `id` передан и файл `<id>.json` существует — истории объединяются: сообщение с совпадающим `id` перезаписывается входящим (позиция сохраняется), сообщения **без** `id` дописываются в конец; `title` берётся из входящего запроса (иначе остаётся прежний), `createdAt` сохраняется от первого сохранения, `updatedAt` обновляется.
- `id` передан, файла нет — создаётся новый файл.
- `id` не передан — сервер генерирует id вида `chat-<yyyyMMdd-HHmmss>-<8 hex>` и возвращает фактический `id` в ответе.

### GET /health

```json
{"status":"ok","storage":{"dir":"storage/chats","writable":true},"llm":{"configured":false,"provider":"openai-compatible","model":"gpt-4.1"}}
```

`llm.configured` = `true`, если задан `LLM_API_KEY`. API-ключ и полный URL провайдера в ответе не раскрываются.

### POST /llm/chat — только для верификации

По умолчанию маршрут **не регистрируется вовсе** (404): включается только при `EXPOSE_LLM_CHAT=true` для QA/верификации шлюза. Единственная точка, где сервис сам вызывает LLM — через Koog, с обязательной записью в журнал `llm-requests.log`.

Запрос (допустимы только роли `system` и `user`):

```json
{"systemPrompt": "Отвечай кратко.", "messages": [{"role": "user", "content": "Привет!"}]}
```

Ответ 200:

```json
{"text":"Здравствуйте!","finishReason":"stop","usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15},"requestId":"8f3c9d2e-..."}
```

`requestId` связывает все записи журнала этого вызова.

### Ошибки

Единый формат: `{"error": {"code": "...", "message": "..."}}`; сообщения проходят маскирование секретов, stacktrace наружу не отдаётся.

| HTTP | code | Условие |
|---|---|---|
| 400 | `INVALID_JSON` | тело не является валидным JSON или не соответствует схеме (в том числе неизвестная роль) |
| 400 | `VALIDATION_FAILED` | недопустимый `id`, `title` > 500 символов, пустой список сообщений, > 500 сообщений, пустой `content` или > 100 000 символов |
| 413 | `PAYLOAD_TOO_LARGE` | тело запроса > 5 МБ |
| 500 | `STORAGE_ERROR` | ошибка чтения/записи файла, нет прав на каталог, повреждённый существующий файл |
| 502 | `LLM_UNAVAILABLE` | LLM-провайдер недоступен (сеть) |
| 502 | `LLM_ERROR` | прочие ошибки вызова LLM |
| 504 | `LLM_TIMEOUT` | превышен таймаут запроса к LLM |
| 500 | `INTERNAL_ERROR` | непредвиденная ошибка сервера |

## Инструмент для LLM (tool calling)

Сервис публикует контракт инструмента в формате OpenAI function calling (`tool/ToolSchema.kt`, имя `save_chat_history`). Как это работает: хост (агент/фреймворк) регистрирует инструмент как function tool с URL `$SERVER_BASE_URL/tools/save-chat` и передаёт LLM:

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
        "title": {"type": "string", "maxLength": 500, "description": "Краткий заголовок/тема диалога (необязательно)."},
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

LLM отвечает `tool_calls`, хост выполняет вызов HTTP POST на эндпоинт и подставляет результат в `tool`-сообщение. Пример фрагмента ответа LLM:

```json
"tool_calls": [
  {
    "id": "call_1",
    "type": "function",
    "function": {
      "name": "save_chat_history",
      "arguments": "{\"id\":\"dialog-42\",\"title\":\"Настройка Ktor\",\"messages\":[{\"role\":\"user\",\"content\":\"Как поднять Ktor?\",\"id\":\"m1\"},{\"role\":\"assistant\",\"content\":\"embeddedServer(Netty) { ... }\",\"id\":\"m2\"}]}"
    }
  }
]
```

Результат вызова хост возвращает модели так:

```json
{"role": "tool", "tool_call_id": "call_1", "content": "{\"ok\":true,\"file\":\"dialog-42.json\",\"path\":\"dialog-42.json\",\"messageCount\":2}"}
```

Примечание для Koog-хостов: при выполнении промптов через Koog инструменты передаются в `PromptExecutor.execute(prompt, model, tools: List<ToolDescriptor>)`; наша схема совместима с генератором `OpenAICompatibleToolDescriptorSchemaGenerator`. Регистрация HTTP-инструмента как `ToolDescriptor` внутри Koog-агента — опция потребителя, не входит в объём этого сервиса.

## Логирование вызовов LLM (обязательное требование)

Каждый вызов LLM проходит через декоратор `LlmLoggingGateway` и пишется JSON-строками (одна строка — одно событие) в `logs/llm-requests.log` через выделенный логгер `llm.requests` (в консоль он не дублируется; общий лог приложения идёт в STDOUT).

События одного вызова (связываются полем `requestId`):

- `llm.request` — перед вызовом: провайдер, `baseUrl` (без query и userinfo), модель, системный промпт и сообщения;
- `llm.response` — после успеха: текст ответа, `finishReason`, счётчики токенов, длительность;
- `llm.error` — при любой ошибке, включая отмену корутины (тип `CANCELLED`).

Примеры строк журнала:

```json
{"ts":"2026-10-08T10:00:00.000Z","event":"llm.request","requestId":"8f3c9d2e-...","provider":"openai-compatible","baseUrl":"https://api.openai.com","model":"gpt-4.1","systemPrompt":"Отвечай кратко.","messages":[{"role":"user","content":"Привет!"}]}
{"ts":"2026-10-08T10:00:02.000Z","event":"llm.response","requestId":"8f3c9d2e-...","durationMs":2000,"finishReason":"stop","text":"Здравствуйте!","usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15}}
{"ts":"2026-10-08T10:00:01.000Z","event":"llm.error","requestId":"8f3c9d2e-...","durationMs":1000,"error":{"type":"LLM_TIMEOUT","message":"Таймаут вызова LLM: ..."}}
```

Ротация (Logback, `SizeAndTimeBasedRollingPolicy`): файл до 10 МБ, архив `llm-requests.<дата>.<номер>.log.gz`, хранение 30 дней, суммарно не более 1 ГБ; запись с немедленным сбросом на диск (`immediateFlush`).

Маскирование секретов (применяется ко всем строковым полям ДО сериализации):

- сам `LLM_API_KEY` в журнал не включается в принципе;
- пары вида `key=value`, `"key":"value"`, `key: value` для ключей из чёрного списка (`api_key`, `apikey`, `authorization`, `bearer`, `token`, `secret`, `password`) → `***MASKED***`;
- токены вида `sk-...` и любые «подозрительно длинные» токены без пробелов (≥ 33 символов) → `***MASKED***`.

## Хранение историй

Один JSON-файл на диалог: `storage/chats/<id>.json`. Файл — человекочитаемый (pretty-print, 4 пробела), `createdAt`/`updatedAt` проставляет сервер; не переданные необязательные поля сообщений сериализуются как `null`. Пример:

```json
{
    "id": "dialog-42",
    "title": "Помощь с настройкой Ktor",
    "messages": [
        {
            "role": "user",
            "content": "Как поднять Ktor на Netty?",
            "id": "m1",
            "timestamp": "2026-10-08T10:00:00Z"
        },
        {
            "role": "assistant",
            "content": "embeddedServer(Netty, port = 8080) { ... }",
            "id": "m2",
            "timestamp": "2026-10-08T10:00:05Z"
        }
    ],
    "createdAt": "2026-10-08T10:00:00Z",
    "updatedAt": "2026-10-08T10:00:05Z"
}
```

Гарантии:

- запись атомарна (временный файл в том же каталоге + атомарный move) — читатель никогда не увидит частично записанный файл; остатки `*.tmp-*` после падения процесса подчищаются при старте;
- на каждый файл — своя блокировка: сохранения одного диалога сериализованы, разные диалоги пишутся параллельно;
- повреждённый существующий файл не перезаписывается молча — возвращается `STORAGE_ERROR`, чтобы не потерять данные.

## Разработка

```bash
./gradlew build   # сборка
./gradlew test    # юнит- и интеграционные тесты (JUnit 5, Ktor testApplication)
```

Реальные вызовы LLM в тестах не выполняются: Koog подменяется фейковым `PromptExecutor`, поэтому ключи и сеть не нужны.

Структура пакетов (кратко):

- `config` — загрузка `application.conf` (HOCON) + env-переменные;
- `model` — модели диалога, DTO, иерархия ошибок;
- `storage` — файловое хранилище (атомарная запись, слияние, блокировки);
- `llm` — шлюз Koog + обязательное логирование; импорты `ai.koog.*` допустимы только здесь;
- `tool` — JSON-схема инструмента `save_chat_history`;
- `routes` — маршруты Ktor и единый формат ошибок;
- `validation` — правила валидации и лимиты.

Подробно — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); цели и ограничения проекта — [docs/PROJECT.md](docs/PROJECT.md).

CI (`.github/workflows/ci.yml`): на push и PR в `main` — сборка и тесты на JDK 17 (`./gradlew build test`), кэширование Gradle, при падении — артефакты отчётов тестов. Секреты в CI не нужны.

## Ограничения и известные особенности

- **Без авторизации** (по умолчанию — решение в task-plan.json): эндпоинты открыты, разворачивать в доверенной сети. Точка расширения — Ktor-плагин Authentication перед маршрутами.
- **Upsert и сообщения без `id`**: при повторном сохранении сообщения БЕЗ `id` дописываются заново. Если LLM каждый раз шлёт полную историю без `id` сообщений, в файле появятся дубли. Шлите стабильные `id` сообщений (или сохраняйте диалог один раз, в конце).
- **`POST /llm/chat` расходует API-бюджет**: если эндпоинт включён (`EXPOSE_LLM_CHAT=true`), любой, кто дотянется до сервера, может тратить средства с вашего ключа. Держите его отключённым в боевом режиме — он только для верификации.
- **Группа `ai.koog` не индексируется search.maven.org**: артефакты физически лежат в Maven Central и резолвятся обычным `mavenCentral()`; актуальные версии проверяйте по `https://repo1.maven.org/maven2/ai/koog/<артефакт>/maven-metadata.xml`.
- Нет БД и поиска по историям — только JSON-файлы.
- Нет потоковой генерации и многошаговых агентов — только одиночные chat-completion вызовы через Koog.
- В боевом режиме сервис сам LLM не вызывает: единственный вызов LLM из сервиса — верификационный `/llm/chat`.
