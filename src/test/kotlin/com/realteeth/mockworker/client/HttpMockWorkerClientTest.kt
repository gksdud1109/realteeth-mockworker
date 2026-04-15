package com.realteeth.mockworker.client

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
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
 * 서킷 브레이커 — OPEN 전이 시 isTransient=true 예외 변환 검증.
 *
 * 401 재시도, 실제 HTTP 왕복 등 외부 의존이 필요한 흐름은
 * WireMock 기반 통합 테스트에서 별도 검증 필요 (TODO).
 */
class HttpMockWorkerClientTest {

    private val restClient: RestClient = mock(RestClient::class.java)
    private val apiKeyProvider: MockWorkerApiKeyProvider = mock(MockWorkerApiKeyProvider::class.java)

    /** classify/toSnapshot 단위 테스트용 — 서킷 브레이커를 비활성화한 레지스트리 */
    private val disabledCb = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowSize(100)
            .minimumNumberOfCalls(100)
            .build(),
    )
    private val client = HttpMockWorkerClient(restClient, apiKeyProvider, disabledCb)

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

    // ─── 서킷 브레이커 ────────────────────────────────────────────────────────

    @Test
    fun `서킷 OPEN 시 isTransient=true MockWorkerException 으로 변환`() {
        // sliding-window=3, minimumCalls=3, failureRate=50% → 3번 모두 실패하면 OPEN
        val tightConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(3)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build()
        val registry = CircuitBreakerRegistry.of(tightConfig)
        val cb = registry.circuitBreaker("mock-worker")

        // 서킷 브레이커를 강제로 OPEN 상태로 전이
        cb.transitionToOpenState()
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)

        val clientWithOpenCb = HttpMockWorkerClient(restClient, apiKeyProvider, registry)

        assertThatThrownBy { clientWithOpenCb.submit("https://img.example.com") }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e ->
                assertThat(e.isTransient).isTrue()
                assertThat(e.message).contains("서킷 오픈")
            })
    }

    @Test
    fun `연속 실패로 서킷이 OPEN 전이 — 이후 호출은 즉시 차단`() {
        // 3번 호출 중 3번 모두 실패(100%) → failureRate(50%) 초과 → OPEN
        val tightConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(3)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build()
        val registry = CircuitBreakerRegistry.of(tightConfig)
        val clientWithCb = HttpMockWorkerClient(restClient, apiKeyProvider, registry)
        val cb = registry.circuitBreaker("mock-worker")

        // 서킷 브레이커에 직접 실패를 기록해 OPEN 전이
        repeat(3) { cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, RuntimeException("5xx")) }
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)

        // OPEN 상태에서 호출 시 isTransient=true
        assertThatThrownBy { clientWithCb.fetch("worker-1") }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isTrue() })
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun restClientEx(statusCode: Int): RestClientResponseException =
        RestClientResponseException("HTTP $statusCode", statusCode, "reason", null, null, null)
}
