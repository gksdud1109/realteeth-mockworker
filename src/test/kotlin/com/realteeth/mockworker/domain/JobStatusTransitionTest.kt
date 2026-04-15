package com.realteeth.mockworker.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class JobStatusTransitionTest {

    private val now = Instant.EPOCH

    private fun pendingJob() = ImageJob.accept("key", "https://img.example.com", "fp", now)

    @Test
    fun `PENDING 에서 IN_PROGRESS 전이 가능`() {
        val job = pendingJob()
        job.markSubmitted("worker-1", now)
        assertThat(job.status).isEqualTo(JobStatus.IN_PROGRESS)
    }

    @Test
    fun `PENDING 에서 FAILED 전이 가능`() {
        val job = pendingJob()
        job.markFailed("제출 실패", now)
        assertThat(job.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `IN_PROGRESS 에서 COMPLETED 전이 가능`() {
        val job = pendingJob()
        job.markSubmitted("worker-1", now)
        job.markCompleted("result", now)
        assertThat(job.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `IN_PROGRESS 에서 FAILED 전이 가능`() {
        val job = pendingJob()
        job.markSubmitted("worker-1", now)
        job.markFailed("폴링 실패", now)
        assertThat(job.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `COMPLETED 는 터미널 상태 — 추가 전이 불가`() {
        val job = pendingJob()
        job.markSubmitted("worker-1", now)
        job.markCompleted("result", now)
        assertThatThrownBy { job.markFailed("오류", now) }
            .isInstanceOf(InvalidJobStateException::class.java)
    }

    @Test
    fun `FAILED 는 터미널 상태 — 추가 전이 불가`() {
        val job = pendingJob()
        job.markFailed("오류", now)
        assertThatThrownBy { job.markSubmitted("worker-1", now) }
            .isInstanceOf(InvalidJobStateException::class.java)
    }

    @Test
    fun `PENDING 에서 COMPLETED 직접 전이 불가`() {
        val job = pendingJob()
        assertThatThrownBy { job.markCompleted("result", now) }
            .isInstanceOf(InvalidJobStateException::class.java)
    }

    @Test
    fun `markSubmitted 시 attemptCount 가 0으로 초기화됨`() {
        val job = pendingJob()
        job.recordTransientFailure(now.plusSeconds(1), "일시 오류", now)
        job.recordTransientFailure(now.plusSeconds(2), "일시 오류", now)
        job.markSubmitted("worker-1", now)
        assertThat(job.attemptCount).isEqualTo(0)
    }

    @Test
    fun `recordProgress 는 failureReason 을 null 로 초기화`() {
        val job = pendingJob()
        job.markSubmitted("worker-1", now)
        job.recordTransientFailure(now.plusSeconds(1), "일시 오류", now)
        job.recordProgress(now.plusSeconds(2), now)
        assertThat(job.failureReason).isNull()
    }

    @Test
    fun `PENDING 상태에서 recordProgress 호출 시 예외`() {
        val job = pendingJob()
        assertThatThrownBy { job.recordProgress(now.plusSeconds(1), now) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
