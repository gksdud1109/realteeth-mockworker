package com.realteeth.mockworker.client

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * HttpMockWorkerClient 핵심 로직 단위 테스트.
 *
 * classify — HTTP 상태 코드 → isTransient 분류 규칙 검증.
 * toSnapshot — 워커 응답 파싱 실패 시 예외 종류 검증.
 *
 * 401 재시도, 실제 HTTP 왕복 등 외부 의존이 필요한 흐름은
 * WireMock 기반 통합 테스트에서 별도 검증 필요 (TODO).
 */
class HttpMockWorkerClientTest {

    private val restClient: RestClient = mock(RestClient::class.java)
    private val apiKeyProvider: MockWorkerApiKeyProvider = mock(MockWorkerApiKeyProvider::class.java)
    private val client = HttpMockWorkerClient(restClient, apiKeyProvider)

    // ─── classify — HTTP 상태 코드 분류 ───────────────────────────────────────

    @Test
    fun `5xx 응답은 일시적 오류로 분류`() {
        val result = client.classify(restClientEx(503), "test")
        assertThat(result.isTransient).isTrue()
    }

    @Test
    fun `429 응답은 일시적 오류로 분류`() {
        val result = client.classify(restClientEx(429), "test")
        assertThat(result.isTransient).isTrue()
    }

    @Test
    fun `4xx 응답 (429 제외) 은 영구 오류로 분류`() {
        listOf(400, 401, 403, 404, 422).forEach { code ->
            val result = client.classify(restClientEx(code), "test")
            assertThat(result.isTransient)
                .describedAs("HTTP $code 는 영구 오류여야 함")
                .isFalse()
        }
    }

    @Test
    fun `네트워크 접근 오류는 일시적 오류로 분류`() {
        val result = client.classify(ResourceAccessException("연결 실패"), "test")
        assertThat(result.isTransient).isTrue()
    }

    @Test
    fun `그 외 예외는 일시적 오류로 분류`() {
        val result = client.classify(RuntimeException("알 수 없음"), "test")
        assertThat(result.isTransient).isTrue()
    }

    // ─── toSnapshot — 응답 본문 파싱 ─────────────────────────────────────────

    @Test
    fun `body가 null이면 isTransient=false 예외`() {
        assertThatThrownBy { client.toSnapshot(null) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `jobId 누락 시 isTransient=false 예외`() {
        assertThatThrownBy { client.toSnapshot(mapOf("status" to "PROCESSING")) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `status 누락 시 isTransient=false 예외`() {
        assertThatThrownBy { client.toSnapshot(mapOf("jobId" to "w-1")) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `알 수 없는 status 값은 isTransient=false 예외`() {
        assertThatThrownBy { client.toSnapshot(mapOf("jobId" to "w-1", "status" to "UNKNOWN")) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `정상 PROCESSING 응답 파싱`() {
        val snapshot = client.toSnapshot(mapOf("jobId" to "w-1", "status" to "PROCESSING"))
        assertThat(snapshot.jobId).isEqualTo("w-1")
        assertThat(snapshot.status).isEqualTo(WorkerJobStatus.PROCESSING)
        assertThat(snapshot.result).isNull()
    }

    @Test
    fun `정상 COMPLETED 응답 파싱 — result 포함`() {
        val snapshot = client.toSnapshot(
            mapOf("jobId" to "w-1", "status" to "COMPLETED", "result" to "처리 결과"),
        )
        assertThat(snapshot.status).isEqualTo(WorkerJobStatus.COMPLETED)
        assertThat(snapshot.result).isEqualTo("처리 결과")
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun restClientEx(statusCode: Int): RestClientResponseException =
        RestClientResponseException("HTTP $statusCode", statusCode, "reason", null, null, null)
}
