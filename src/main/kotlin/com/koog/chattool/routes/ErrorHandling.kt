package com.koog.chattool.routes

import com.koog.chattool.llm.SecretMasker
import com.koog.chattool.model.AppException
import com.koog.chattool.model.ErrorBody
import com.koog.chattool.model.ErrorResponse
import kotlinx.coroutines.CancellationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/**
 * Единый формат ошибок API (см. docs/ARCHITECTURE.md, раздел 8).
 *
 * Маршруты НЕ ловят исключения точечно: любой AppException перехватывается здесь
 * и превращается в JSON {"error": {"code": ..., "message": ...}} с HTTP-статусом,
 * заданным в самом исключении. Непредвиденные ошибки → 500 INTERNAL_ERROR,
 * внутренности клиенту не раскрываются.
 */
fun Application.installErrorHandling() {
    val log = LoggerFactory.getLogger("com.koog.chattool.ErrorHandling")

    install(StatusPages) {
        // Ожидаемые ошибки приложения: валидация, хранилище, вызовы LLM.
        // Сообщение маскируется: в нём может быть URL провайдера или текст с секретами.
        exception<AppException> { call, e ->
            if (e.httpStatus.value >= 500) {
                log.warn("Ошибка ${e.code}: ${SecretMasker.mask(e.message)}")
            }
            call.respond(e.httpStatus, ErrorResponse(ErrorBody(e.code, SecretMasker.mask(e.message))))
        }

        // Отмену корутины НЕ превращаем в 500: пробрасываем как есть,
        // чтобы не нарушать structured concurrency (Ktor корректно завершит запрос).
        exception<CancellationException> { _, cause ->
            throw cause
        }

        // Всё остальное — непредвиденные ошибки: логируем полностью, клиенту отдаём общее сообщение.
        exception<Throwable> { call, e ->
            log.error("Непредвиденная ошибка при обработке запроса ${call.request.local.uri}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorBody("INTERNAL_ERROR", "Внутренняя ошибка сервера")),
            )
        }
    }
}
