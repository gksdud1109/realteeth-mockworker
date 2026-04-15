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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * IN_PROGRESS 상태 작업의 진행 상황을 Mock Worker 에 폴링.
 *
 * 데드라인: 작업이 [MockWorkerProperties.Poll.deadline] 을 초과해 IN_PROGRESS 상태이면 FAILED 처리.
 * 데드라인은 updatedAt 기준으로 평가되므로 워커가 PROCESSING 을 계속 응답하는
 * 한 삭제되지 않는다.
 *
 * 일시적 폴링 실패는 지수 백오프로 nextAttemptAt 을 연장.
 * 영구 실패(429 제외 4xx)는 즉시 FAILED 처리.
 */
@Component
class JobPoller(
    private val repository: ImageJobRepository,
    private val client: MockWorkerClient,
    private val props: MockWorkerProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // 폴링은 maxAttempts 없음 — 데드라인 기반으로 만료
    private val retryPolicy = RetryPolicy(
        initial = props.poll.initialInterval,
        ceiling = props.poll.maxInterval,
        maxAttempts = null,
        phase = "poll",
    )

    @Scheduled(fixedDelayString = "\${mock-worker.poll.scheduler-delay-ms:1000}")
    @SchedulerLock(name = "job-poller", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    fun runOnce() {
        val dueIds = tx.execute { _ ->
            repository.findDueByStatus(JobStatus.IN_PROGRESS, Instant.now(clock), PageRequest.of(0, BATCH_SIZE))
                .map { it.id }
        } ?: return

        dueIds.forEach { id ->
            try { pollOne(id) } catch (e: Exception) {
                log.warn("poll 루프 예상치 못한 오류 (job={})", id, e)
            }
        }
    }

    private fun pollOne(id: String) {
        val job = repository.findById(id).orElse(null)
        if (job == null || job.status != JobStatus.IN_PROGRESS) return

        if (job.workerJobId == null) {
            log.error("job={} 이 workerJobId 없이 IN_PROGRESS 상태 — 불변식 위반", id)
            return
        }

        // 데드라인 사전 체크 — 스테일 스냅샷 기반이므로 낙관적 필터 역할만 함.
        // 실제 FAILED 판정은 TX 내부에서 fresh.updatedAt 기준으로 재검증한다.
        // 이중 검증 이유: 외부 HTTP 호출 전에 명백한 초과 케이스를 걸러내되,
        // 멀티 인스턴스 환경에서 스테일 읽기로 인한 오판을 막기 위해 TX 내부 재확인이 필수.
        val deadline = props.poll.deadline
        if (deadline != null && job.updatedAt.plus(deadline).isBefore(Instant.now(clock))) {
            tx.executeWithoutResult { _ ->
                val fresh = repository.findById(id).orElse(null)
                if (fresh == null || fresh.status != JobStatus.IN_PROGRESS) return@executeWithoutResult
                val t = Instant.now(clock)
                // fresh.updatedAt 기준 재검증 — 스테일 스냅샷으로 인한 오판 방지
                if (fresh.updatedAt.plus(deadline).isBefore(t)) {
                    fresh.markFailed("poll 데드라인 초과", t)
                    repository.save(fresh)
                    log.warn("job={} poll 데드라인 초과, FAILED 처리", id)
                }
            }
            return
        }

        val snapshot = try {
            client.fetch(job.workerJobId!!)
        } catch (e: MockWorkerException) {
            applyRetry(id, e)
            return
        }

        // OptimisticLockingFailureException 은 커밋 시점(람다 반환 후)에 발생하므로
        // 람다 내부가 아닌 executeWithoutResult 호출 전체를 감싸야 한다.
        try {
            tx.executeWithoutResult { _ ->
                val fresh = repository.findById(id).orElse(null)
                if (fresh == null || fresh.status != JobStatus.IN_PROGRESS) return@executeWithoutResult
                val t = Instant.now(clock)
                when (snapshot.status) {
                    WorkerJobStatus.COMPLETED -> fresh.markCompleted(snapshot.result, t)
                    WorkerJobStatus.FAILED -> fresh.markFailed("워커가 FAILED 응답", t)
                    WorkerJobStatus.PROCESSING -> {
                        val backoff = BackoffCalculator.next(
                            props.poll.initialInterval,
                            props.poll.maxInterval,
                            fresh.attemptCount,
                        )
                        fresh.recordProgress(t.plus(backoff), t)
                    }
                }
                repository.save(fresh)
            }
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.info("poll 동시 업데이트 감지 (job={}), 다음 틱에서 재시도", id)
        }
    }

    private fun applyRetry(id: String, e: MockWorkerException) {
        tx.executeWithoutResult { _ ->
            val fresh = repository.findById(id).orElse(null)
            if (fresh == null || fresh.status != JobStatus.IN_PROGRESS) return@executeWithoutResult
            val failed = retryPolicy.apply(fresh, e, Instant.now(clock))
            repository.save(fresh)
            if (failed) log.warn("job={} poll 영구 실패: {}", id, fresh.failureReason)
        }
    }

    companion object {
        private const val BATCH_SIZE = 20
    }
}
