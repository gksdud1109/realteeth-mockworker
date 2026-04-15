package com.realteeth.mockworker.client

/**
 * [MockWorkerClient] 호출 실패 시 던지는 예외.
 *
 * [isTransient] 값으로 재시도 여부를 결정한다:
 *   - true: 네트워크 I/O 오류, 5xx, 429, 타임아웃 — 재시도 가능
 *   - false: 4xx(429 제외), 응답 파싱 실패 — 재시도해도 해결 안 됨
 */
class MockWorkerException(
    message: String,
    val isTransient: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
