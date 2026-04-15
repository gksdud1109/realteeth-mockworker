package com.realteeth.mockworker.client

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.InstanceOfAssertFactories
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

class HttpMockWorkerClientTest {

    private val restClient: RestClient = mock(RestClient::class.java)
    private val apiKeyProvider: MockWorkerApiKeyProvider = mock(MockWorkerApiKeyProvider::class.java)

    private val disabledCb = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100).build(),
    )
    private val client = HttpMockWorkerClient(restClient, apiKeyProvider, disabledCb)

    // ─── classify ─────────────────────────────────────────────────────────────

    @Test
    fun `5xx 응답은 일시적 오류로 분류`() {
        assertThat(client.classify(restClientEx(503), "test").isTransient).isTrue()
    }

    @Test
    fun `429 응답은 일시적 오류로 분류`() {
        assertThat(client.classify(restClientEx(429), "test").isTransient).isTrue()
    }

    @Test
    fun `4xx 응답은 영구 오류로 분류`() {
        listOf(400, 401, 403, 404, 422).forEach { code ->
            assertThat(client.classify(restClientEx(code), "test").isTransient)
                .describedAs("HTTP $code 는 영구 오류여야 함")
                .isFalse()
        }
    }

    @Test
    fun `네트워크 오류는 일시적 오류로 분류`() {
        assertThat(client.classify(ResourceAccessException("연결 실패"), "test").isTransient).isTrue()
    }

    @Test
    fun `그 외 예외는 일시적 오류로 분류`() {
        assertThat(client.classify(RuntimeException("알 수 없음"), "test").isTransient).isTrue()
    }

    @Test
    fun `MockWorkerException 입력 시 isTransient 보존`() {
        val permanent = MockWorkerException("파싱 오류", isTransient = false)
        assertThat(client.classify(permanent, "test")).isSameAs(permanent)

        val transient = MockWorkerException("5xx", isTransient = true)
        assertThat(client.classify(transient, "test")).isSameAs(transient)
    }

    // ─── toSnapshot ───────────────────────────────────────────────────────────

    @Test
    fun `body가 null이면 영구 오류`() {
        assertThatThrownBy { client.toSnapshot(null) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `jobId 누락 시 영구 오류`() {
        assertThatThrownBy { client.toSnapshot(mapOf("status" to "PROCESSING")) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `status 누락 시 영구 오류`() {
        assertThatThrownBy { client.toSnapshot(mapOf("jobId" to "w-1")) }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isFalse() })
    }

    @Test
    fun `알 수 없는 status 값은 영구 오류`() {
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
    fun `정상 COMPLETED 응답 파싱`() {
        val snapshot = client.toSnapshot(mapOf("jobId" to "w-1", "status" to "COMPLETED", "result" to "처리 결과"))
        assertThat(snapshot.status).isEqualTo(WorkerJobStatus.COMPLETED)
        assertThat(snapshot.result).isEqualTo("처리 결과")
    }

    // ─── 서킷 브레이커 ────────────────────────────────────────────────────────

    @Test
    fun `서킷 OPEN 시 isTransient=true 예외 반환`() {
        val registry = tightRegistry()
        registry.circuitBreaker("mock-worker").transitionToOpenState()

        assertThatThrownBy { HttpMockWorkerClient(restClient, apiKeyProvider, registry).submit("https://img.example.com") }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e ->
                assertThat(e.isTransient).isTrue()
                assertThat(e.message).contains("서킷 오픈")
            })
    }

    @Test
    fun `연속 실패로 서킷 OPEN 전이`() {
        val registry = tightRegistry()
        val cb = registry.circuitBreaker("mock-worker")

        repeat(3) { cb.onError(0, TimeUnit.MILLISECONDS, RuntimeException("5xx")) }
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)

        assertThatThrownBy { HttpMockWorkerClient(restClient, apiKeyProvider, registry).fetch("worker-1") }
            .asInstanceOf(InstanceOfAssertFactories.throwable(MockWorkerException::class.java))
            .satisfies({ e -> assertThat(e.isTransient).isTrue() })
    }

    @Test
    fun `영구 오류는 서킷 실패 집계에서 제외`() {
        val registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(3).minimumNumberOfCalls(3).failureRateThreshold(50f)
                .ignoreException { t -> t is MockWorkerException && !t.isTransient }
                .build(),
        )
        val cb = registry.circuitBreaker("mock-worker")

        repeat(3) { cb.onError(0, TimeUnit.MILLISECONDS, MockWorkerException("4xx", isTransient = false)) }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(cb.metrics.numberOfFailedCalls).isEqualTo(0)
    }

    @Test
    fun `HALF_OPEN에서 연속 성공 시 CLOSED 복구`() {
        val cb = tightRegistry().circuitBreaker("mock-worker")
        cb.transitionToOpenState()
        cb.transitionToHalfOpenState()

        repeat(3) { cb.onSuccess(0, TimeUnit.MILLISECONDS) }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `HALF_OPEN에서 실패율 초과 시 OPEN 재전이`() {
        val cb = tightRegistry().circuitBreaker("mock-worker")
        cb.transitionToOpenState()
        cb.transitionToHalfOpenState()

        cb.onError(0, TimeUnit.MILLISECONDS, RuntimeException("fail"))
        cb.onError(0, TimeUnit.MILLISECONDS, RuntimeException("fail"))
        cb.onSuccess(0, TimeUnit.MILLISECONDS)

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun tightRegistry(): CircuitBreakerRegistry = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowSize(3).minimumNumberOfCalls(3)
            .failureRateThreshold(50f).permittedNumberOfCallsInHalfOpenState(3)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build(),
    )

    private fun restClientEx(statusCode: Int): RestClientResponseException =
        RestClientResponseException("HTTP $statusCode", statusCode, "reason", null, null, null)
}
