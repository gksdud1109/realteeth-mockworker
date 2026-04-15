package com.realteeth.mockworker.client

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Component
class HttpMockWorkerClient(
    private val restClient: RestClient,
    private val apiKeyProvider: MockWorkerApiKeyProvider,
) : MockWorkerClient {

    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    override fun submit(imageUrl: String): WorkerJobSnapshot = call("submit") {
        val body = restClient.post()
            .uri("/process")
            .header("X-API-KEY", apiKeyProvider.get())
            .body(mapOf("imageUrl" to imageUrl))
            .retrieve()
            .body(Map::class.java) as? Map<*, *>
        toSnapshot(body)
    }

    @Suppress("UNCHECKED_CAST")
    override fun fetch(workerJobId: String): WorkerJobSnapshot = call("fetch") {
        val body = restClient.get()
            .uri("/process/{id}", workerJobId)
            .header("X-API-KEY", apiKeyProvider.get())
            .retrieve()
            .body(Map::class.java) as? Map<*, *>
        toSnapshot(body)
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
