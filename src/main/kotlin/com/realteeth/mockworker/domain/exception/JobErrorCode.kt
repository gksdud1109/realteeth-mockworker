package com.realteeth.mockworker.domain.exception

import org.springframework.http.HttpStatus

enum class JobErrorCode(
    override val status: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "작업을 찾을 수 없습니다"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "idempotency_conflict", "동일 멱등성 키로 다른 페이로드가 전달됨"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "invalid_state", "허용되지 않는 상태 전이"),
}
