package com.realteeth.mockworker.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * 이미지 처리 작업의 애그리거트 루트.
 *
 * 모든 상태 전이는 이 클래스 내부에서만 수행된다:
 *   - 상태 전이는 [JobStatus.canTransitionTo] 를 따른다
 *   - 종료 상태(COMPLETED/FAILED)는 변경 불가
 *   - result 는 COMPLETED 전이 시에만 설정
 *   - failureReason 은 FAILED 전이 시에만 설정
 */
@Entity
@Table(
    name = "image_job",
    indexes = [
        Index(name = "ux_image_job_client_request_key", columnList = "client_request_key", unique = true),
        Index(name = "ix_image_job_status_next_attempt", columnList = "status, next_attempt_at"),
    ],
)
class ImageJob protected constructor() {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    var id: String = ""; internal set

    /** 클라이언트가 제공하는 멱등성 키. 동일 키 + 동일 이미지 → 기존 작업 반환. */
    @Column(name = "client_request_key", length = 128, nullable = false, updatable = false)
    var clientRequestKey: String = ""; internal set

    @Column(name = "image_url", length = 2048, nullable = false, updatable = false)
    var imageUrl: String = ""; internal set

    /** 요청 페이로드의 해시값. 동일 멱등성 키로 다른 페이로드가 들어온 경우를 감지하는 데 사용. */
    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    var requestFingerprint: String = ""; internal set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    var status: JobStatus = JobStatus.PENDING; internal set

    /** Mock Worker 가 반환한 작업 ID. 제출 성공 전까지 null. */
    @Column(name = "worker_job_id", length = 128)
    var workerJobId: String? = null; internal set

    @Column(name = "result", length = 4096)
    var result: String? = null; internal set

    @Column(name = "failure_reason", length = 1024)
    var failureReason: String? = null; internal set

    /** 제출/폴링 시도 횟수. 백오프 계산과 최대 재시도 판단에 사용. */
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0; internal set

    /** 백그라운드 워커가 이 행을 다시 처리할 수 있는 가장 이른 시각. */
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.EPOCH; internal set

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH; internal set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH; internal set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0; internal set

    companion object {
        fun accept(clientRequestKey: String, imageUrl: String, fingerprint: String, now: Instant): ImageJob =
            ImageJob().apply {
                id = UUID.randomUUID().toString()
                this.clientRequestKey = clientRequestKey
                this.imageUrl = imageUrl
                requestFingerprint = fingerprint
                status = JobStatus.PENDING
                attemptCount = 0
                nextAttemptAt = now
                createdAt = now
                updatedAt = now
            }
    }

    fun markSubmitted(workerJobId: String, now: Instant) {
        requireTransition(JobStatus.IN_PROGRESS)
        require(workerJobId.isNotBlank()) { "IN_PROGRESS 전이 시 workerJobId 가 필요합니다" }
        status = JobStatus.IN_PROGRESS
        this.workerJobId = workerJobId
        attemptCount = 0 // 폴링 단계 진입 시 재시도 카운터 초기화
        nextAttemptAt = now
        updatedAt = now
    }

    fun markCompleted(result: String?, now: Instant) {
        requireTransition(JobStatus.COMPLETED)
        status = JobStatus.COMPLETED
        this.result = result
        updatedAt = now
    }

    fun markFailed(reason: String, now: Instant) {
        requireTransition(JobStatus.FAILED)
        status = JobStatus.FAILED
        failureReason = reason.truncate(1024)
        updatedAt = now
    }

    /**
     * 일시적 오류를 기록하고 다음 재시도 시각을 설정. 상태는 변경하지 않는다.
     * [failureReason] 에 마지막 오류 내용을 저장해 운영자가 확인할 수 있게 한다.
     */
    fun recordTransientFailure(nextAttemptAt: Instant, reason: String, now: Instant) {
        check(!status.isTerminal) { "종료 상태 $status 에서는 재시도를 기록할 수 없습니다" }
        attemptCount++
        this.nextAttemptAt = nextAttemptAt
        failureReason = reason.truncate(1024)
        updatedAt = now
    }

    /**
     * 워커가 PROCESSING 을 응답한 경우 다음 폴링 시각을 갱신.
     * [recordTransientFailure] 와 달리 [failureReason] 을 null 로 초기화한다.
     * 정상 진행 중이므로 오류 상태가 아님.
     */
    fun recordProgress(nextPollAt: Instant, now: Instant) {
        check(!status.isTerminal) { "종료 상태 $status 에서는 폴링을 진행할 수 없습니다" }
        check(status == JobStatus.IN_PROGRESS) { "recordProgress 는 IN_PROGRESS 상태에서만 호출 가능합니다 (현재: $status)" }
        attemptCount++
        nextAttemptAt = nextPollAt
        failureReason = null
        updatedAt = now
    }

    private fun requireTransition(next: JobStatus) {
        if (!status.canTransitionTo(next)) {
            throw InvalidJobStateException("허용되지 않는 상태 전이: $status → $next (job=$id)")
        }
    }

    private fun String.truncate(max: Int) = if (length <= max) this else substring(0, max)
}
