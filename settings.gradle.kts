// Корневой файл настроек Gradle: имя проекта и репозитории зависимостей.

// Репозитории плагинов: Kotlin, serialization и др. ищем в Maven Central и на Plugin Portal.
// В settings-файле pluginManagement должен идти ПЕРЕД блоком plugins.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Автопровижининг JDK: если на машине нет нужной версии (у нас jvmToolchain(17)),
    // Gradle сам скачает её с foojay API и закэширует в ~/.gradle/jdks.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "koog-chat-tool"

// Все зависимости проекта (Koog, Ktor, Logback и т.д.) резолвятся из Maven Central.
// ВАЖНО: артефакты ai.koog физически лежат в Maven Central, хотя и не индексируются
// поиском search.maven.org (см. docs/ARCHITECTURE.md, раздел 2).
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
