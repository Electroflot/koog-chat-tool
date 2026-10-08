// Скрипт сборки koog-chat-tool.
// Версии выровнены с той сборкой, на которой собран Koog 1.3.0
// (подробности — docs/ARCHITECTURE.md, разделы 2 и 15).

plugins {
    // Kotlin JVM — язык проекта (JDK 17+).
    kotlin("jvm") version "2.3.10"
    // Плагин kotlinx.serialization — генерация сериализаторов для @Serializable-моделей.
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "com.koog.chattool"
version = "0.1.0"

// Версии ключевых фреймворков.
val koogVersion = "1.3.0"   // стабильный зонтичный артефакт Koog (JetBrains)
val ktorVersion = "3.3.3"   // Ktor server (Netty)

repositories {
    mavenCentral()
}

dependencies {
    // Koog — ВСЕ вызовы LLM в проекте выполняются только через него
    // (единственная точка входа — llm/KoogLlmGateway.kt, см. T5).
    implementation("ai.koog:koog-agents:$koogVersion")

    // Ktor server — маршрутизация HTTP-запросов.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    // ContentNegotiation + kotlinx-json: автоматическая (де)сериализация тел запросов/ответов.
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    // StatusPages: единый формат ошибок API (см. routes/ErrorHandling.kt).
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Сериализация JSON и корутины.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // HOCON-конфигурация: application.conf + переопределение env-переменными.
    implementation("com.typesafe:config:1.4.3")

    // Логирование: общий лог — в консоль, запросы/ответы LLM — в файл
    // logs/llm-requests.log (настройка в src/main/resources/logback.xml).
    implementation("ch.qos.logback:logback-classic:1.6.5")

    // Тесты: JUnit 5, Ktor testApplication, kotlinx-coroutines-test.
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Компилируем под JDK 17 (требование Koog и проекта).
kotlin {
    jvmToolchain(17)
}

// Запуск тестов через JUnit Platform.
tasks.test {
    useJUnitPlatform()
}

application {
    // Точка входа: функция main в Application.kt.
    mainClass.set("com.koog.chattool.ApplicationKt")
}
