package com.realteeth.mockworker.web

import com.realteeth.mockworker.domain.exception.BusinessException
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun businessException(e: BusinessException): ResponseEntity<ErrorResponse> =
        build(e.errorCode.status, e.errorCode.code, e.message)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun notReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        build(HttpStatus.BAD_REQUEST, "bad_request", "요청 본문을 읽을 수 없습니다")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> =
        build(
            HttpStatus.BAD_REQUEST,
            "validation_failed",
            e.bindingResult.allErrors.firstOrNull()?.defaultMessage ?: "잘못된 요청입니다",
        )

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 예외", e)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "내부 오류가 발생했습니다")
    }

    private fun build(status: HttpStatus, code: String, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(
                timestamp = Instant.now().toString(),
                status = status.value(),
                error = code,
                message = message ?: "",
            ),
        )

    data class ErrorResponse(
        val timestamp: String,
        val status: Int,
        val error: String,
        val message: String,
    )
}
