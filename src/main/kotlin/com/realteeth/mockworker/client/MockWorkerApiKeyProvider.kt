package com.realteeth.mockworker.client

import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Mock Worker 가 발급한 API 키를 캐시.
 *
 * 스레드 안전 계약:
 *   - [get]: AtomicReference 로 빠른 경로 조회; 없으면 synchronized 발급 호출.
 *   - [refresh]: 캐시 상태와 무관하게 항상 새 키 발급.
 *     [issueAndCache] 와 동일한 락을 사용해 clear → re-fetch 사이의 경쟁 조건 방지.
 *
 * [MockWorkerProperties.apiKey] 가 설정된 경우 원격 발급을 생략한다.
 */
@Component
class MockWorkerApiKeyProvider(
    private val props: MockWorkerProperties,
    private val restClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cached = AtomicReference<String?>()

    init {
        if (!props.apiKey.isNullOrBlank()) cached.set(props.apiKey)
    }

    fun get(): String = cached.get() ?: issueAndCache()

    /**
     * 새 키를 강제 발급하고 캐시를 갱신.
     * [issueAndCache] 와 동일한 락을 사용해 clear → re-fetch 사이에 다른 스레드가 끼어드는 것을 방지.
     */
    @Synchronized
    fun refresh(): String {
        cached.set(null)
        return fetchFromRemote().also { cached.set(it) }
    }

    @Synchronized
    private fun issueAndCache(): String {
        // 더블 체크: 락 대기 중 다른 스레드가 이미 발급했을 수 있음.
        cached.get()?.let { return it }
        return fetchFromRemote().also { cached.set(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchFromRemote(): String {
        log.info("mock-worker API 키 발급 중 (candidate={})", props.candidateName)
        return try {
            val body = restClient.post()
                .uri("/auth/issue-key")
                .body(mapOf("candidateName" to props.candidateName, "email" to props.email))
                .retrieve()
                .body(Map::class.java) as? Map<String, Any?>
            (body?.get("apiKey") as? String)
                ?: throw MockWorkerException("issue-key 응답이 비어있음", false)
        } catch (e: MockWorkerException) {
            throw e
        } catch (e: Exception) {
            throw MockWorkerException("API 키 발급 실패", true, e)
        }
    }
}
