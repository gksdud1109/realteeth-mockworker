package com.realteeth.mockworker.service

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.client.MockWorkerException
import com.realteeth.mockworker.client.MockWorkerProperties
import com.realteeth.mockworker.client.WorkerJobStatus
import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.ImageJobRepository
import com.realteeth.mockworker.domain.JobStatus
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * PENDING 작업을 Mock Worker 에 제출하는 스케줄러.
 *
 * 트랜잭션 경계: 외부 HTTP 호출은 DB 트랜잭션 밖에서 수행.
 * 결과 저장 시에만 짧은 트랜잭션을 열어 워커 응답 대기 중 커넥션을 점유하지 않는다.
 *
 * 동시성: @Version 낙관적 락으로 행을 보호. 다른 인스턴스나 폴러가 먼저 수정한 경우
 * OptimisticLock 으로 저장 실패 → 다음 틱에서 재시도.
 *
 * 워커가 요청을 수신했지만 jobId 저장 전에 크래시가 발생하면 행이 PENDING 으로 남아
 * 재제출된다 (중복 워커 작업 가능). Mock Worker 에 멱등성 키가 없어 구조적으로 방지 불가.
 */
@Component
class JobSubmitter(
    private val repository: ImageJobRepository,
    private val client: MockWorkerClient,
    props: MockWorkerProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val retryPolicy = RetryPolicy(
        initial = props.submit.initialBackoff,
        ceiling = props.submit.maxBackoff,
        maxAttempts = props.submit.maxAttempts,
        phase = "submit",
    )

    @Scheduled(fixedDelayString = "\${mock-worker.submit.scheduler-delay-ms:1000}")
    fun runOnce() {
        val dueIds = tx.execute { _ ->
            repository.findDueByStatus(JobStatus.PENDING, Instant.now(clock), PageRequest.of(0, BATCH_SIZE))
                .map { it.id }
        } ?: return

        dueIds.forEach { id ->
            try { submitOne(id) } catch (e: Exception) {
                log.warn("submit 루프 예상치 못한 오류 (job={})", id, e)
            }
        }
    }

    private fun submitOne(id: String) {
        // 락 밖에서 재조회해 최신 상태를 가져옴; 저장은 @Version 으로 보호.
        val job = repository.findById(id).orElse(null)
        if (job == null || job.status != JobStatus.PENDING) return

        val snapshot = try {
            client.submit(job.imageUrl)
        } catch (e: MockWorkerException) {
            applyRetry(id, e)
            return
        }

        // 워커가 작은 입력에 대해 동기적으로 COMPLETED/FAILED 를 응답할 수 있음.
        // OptimisticLockingFailureException 은 커밋 시점(람다 반환 후)에 발생하므로
        // 람다 내부가 아닌 executeWithoutResult 호출 전체를 감싸야 한다.
        try {
            tx.executeWithoutResult { _ ->
                val fresh = repository.findById(id).orElse(null)
                if (fresh == null || fresh.status != JobStatus.PENDING) return@executeWithoutResult
                val now = Instant.now(clock)
                fresh.markSubmitted(snapshot.jobId, now)
                when (snapshot.status) {
                    WorkerJobStatus.COMPLETED -> fresh.markCompleted(snapshot.result, now)
                    WorkerJobStatus.FAILED -> fresh.markFailed("워커가 제출 시 FAILED 응답", now)
                    WorkerJobStatus.PROCESSING -> Unit
                }
                repository.save(fresh)
            }
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.info("submit 동시 업데이트 감지 (job={}), 다음 틱에서 재시도", id)
        }
    }

    private fun applyRetry(id: String, e: MockWorkerException) {
        tx.executeWithoutResult { _ ->
            val fresh = repository.findById(id).orElse(null)
            if (fresh == null || fresh.status != JobStatus.PENDING) return@executeWithoutResult
            val failed = retryPolicy.apply(fresh, e, Instant.now(clock))
            repository.save(fresh)
            if (failed) log.warn("job={} submit 영구 실패: {}", id, fresh.failureReason)
        }
    }

    companion object {
        private const val BATCH_SIZE = 10
    }
}
