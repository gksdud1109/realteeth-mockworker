package com.realteeth.mockworker.client

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Mock Worker HTTP 클라이언트.
 *
 * 서킷 브레이커([CircuitBreaker])로 submit/fetch 호출을 감싼다.
 * Mock Worker 장애가 지속되면 서킷이 OPEN 되어 즉시 예외를 반환함으로써:
 *   - Mock Worker에 대한 무의미한 호출을 차단해 복구 시간 확보
 *   - 스케줄러 배치 처리 지연 방지 (기다리지 않고 빠르게 실패)
 *
 * 서킷 OPEN 시 [MockWorkerException.isTransient] = true 로 던져
 * [com.realteeth.mockworker.service.RetryPolicy] 의 재시도 흐름과 연동된다.
 */
@Component
class HttpMockWorkerClient(
    private val restClient: RestClient,
    private val apiKeyProvider: MockWorkerApiKeyProvider,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : MockWorkerClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cb: CircuitBreaker = circuitBreakerRegistry.circuitBreaker("mock-worker")

    @Suppress("UNCHECKED_CAST")
    override fun submit(imageUrl: String): WorkerJobSnapshot = withCircuitBreaker("submit") {
        call("submit") {
            val body = restClient.post()
                .uri("/process")
                .header("X-API-KEY", apiKeyProvider.get())
                .body(mapOf("imageUrl" to imageUrl))
                .retrieve()
                .body(Map::class.java) as? Map<*, *>
            toSnapshot(body)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun fetch(workerJobId: String): WorkerJobSnapshot = withCircuitBreaker("fetch") {
        call("fetch") {
            val body = restClient.get()
                .uri("/process/{id}", workerJobId)
                .header("X-API-KEY", apiKeyProvider.get())
                .retrieve()
                .body(Map::class.java) as? Map<*, *>
            toSnapshot(body)
        }
    }

    /**
     * 서킷 브레이커로 작업을 감싼다.
     *
     * [MockWorkerException.isTransient] = false 인 영구 오류는 실패로 집계하되
     * 서킷을 여는 데 기여하지 않도록 [CircuitBreaker.ignoreException]에서 제외한다.
     * 4xx(429 제외) 같은 영구 오류는 반복되어도 외부 서비스 '장애' 신호가 아니기 때문이다.
     */
    private fun withCircuitBreaker(label: String, op: () -> WorkerJobSnapshot): WorkerJobSnapshot {
        return try {
            cb.executeSupplier(op)
        } catch (e: CallNotPermittedException) {
            log.warn("mock-worker 서킷 OPEN — {} 호출 차단됨", label)
            throw MockWorkerException("mock-worker 서킷 오픈 — $label 차단됨", true, e)
        }
    }

    private fun call(label: String, op: () -> WorkerJobSnapshot): WorkerJobSnapshot {
        return try {
            op()
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 401) {
                // 키 만료 가능성 — 한 번 갱신 후 재시도
                log.warn("mock-worker 401 응답 ({}), API 키 갱신 후 재시도", label)
                apiKeyProvider.refresh()
                try { op() } catch (inner: Exception) { throw classify(inner, label) }
            } else {
                throw classify(e, label)
            }
        } catch (e: ResourceAccessException) {
            // 네트워크/IO/타임아웃
            throw MockWorkerException("mock-worker $label 네트워크 오류", true, e)
        } catch (e: Exception) {
            throw MockWorkerException("mock-worker $label 예상치 못한 오류", true, e)
        }
    }

    internal fun classify(e: Exception, label: String): MockWorkerException = when (e) {
        is RestClientResponseException -> {
            val code = e.statusCode.value()
            MockWorkerException(
                "mock-worker $label HTTP $code: ${e.responseBodyAsString}",
                code == 429 || code >= 500,
                e,
            )
        }
        is ResourceAccessException -> MockWorkerException("mock-worker $label 네트워크 오류", true, e)
        else -> MockWorkerException("mock-worker $label 예상치 못한 오류", true, e)
    }

    internal fun toSnapshot(body: Map<*, *>?): WorkerJobSnapshot {
        body ?: throw MockWorkerException("mock-worker 응답 body 없음", false)
        val id = body["jobId"] ?: throw MockWorkerException("mock-worker 응답 파싱 실패: $body", false)
        val status = body["status"] ?: throw MockWorkerException("mock-worker 응답 파싱 실패: $body", false)
        val result = body["result"] as? String
        val parsed = try {
            WorkerJobStatus.valueOf(status.toString())
        } catch (ex: IllegalArgumentException) {
            throw MockWorkerException("알 수 없는 worker 상태: $status", false)
        }
        return WorkerJobSnapshot(id.toString(), parsed, result)
    }
}
