package com.realteeth.mockworker.service

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.client.MockWorkerException
import com.realteeth.mockworker.client.WorkerJobSnapshot
import com.realteeth.mockworker.client.WorkerJobStatus
import com.realteeth.mockworker.domain.ImageJob
import com.realteeth.mockworker.domain.ImageJobRepository
import com.realteeth.mockworker.domain.JobStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class JobSubmitterTest {

    @Autowired lateinit var submitter: JobSubmitter
    @Autowired lateinit var repository: ImageJobRepository
    @MockitoBean lateinit var client: MockWorkerClient
    @MockitoBean lateinit var clock: Clock

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenAnswer { Instant.now() }
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
    }

    private fun savePending(key: String = "key"): ImageJob =
        repository.saveAndFlush(ImageJob.accept(key, "https://img.example.com", "fp", Instant.now()))

    @Test
    fun `submit 성공 시 IN_PROGRESS 전이`() {
        val job = savePending()
        whenever(client.submit(any())).thenReturn(WorkerJobSnapshot("w-1", WorkerJobStatus.PROCESSING, null))

        submitter.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.IN_PROGRESS)
        assertThat(updated.workerJobId).isEqualTo("w-1")
    }

    @Test
    fun `워커가 동기적으로 COMPLETED 응답 시 즉시 COMPLETED 전이`() {
        val job = savePending()
        whenever(client.submit(any()))
            .thenReturn(WorkerJobSnapshot("w-1", WorkerJobStatus.COMPLETED, "결과"))

        submitter.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(updated.result).isEqualTo("결과")
    }

    @Test
    fun `일시적 오류 시 PENDING 유지 및 재시도 예약`() {
        val job = savePending()
        whenever(client.submit(any())).thenThrow(MockWorkerException("5xx", true))

        submitter.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.PENDING)
        assertThat(updated.attemptCount).isEqualTo(1)
        assertThat(updated.failureReason).isNotNull()
    }

    @Test
    fun `영구 오류 시 즉시 FAILED 전이`() {
        val job = savePending()
        whenever(client.submit(any())).thenThrow(MockWorkerException("4xx", false))

        submitter.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `최대 재시도 초과 시 FAILED 전이`() {
        val job = savePending()
        // maxAttempts=5 이므로 4회 선행 실패 후 5번째 실패에서 FAILED
        repeat(4) { job.recordTransientFailure(Instant.EPOCH, "오류", Instant.EPOCH) }
        repository.saveAndFlush(job)

        whenever(client.submit(any())).thenThrow(MockWorkerException("5xx", true))

        submitter.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `이미 IN_PROGRESS인 작업은 submit 건너뜀 — 상태 가드 방어`() {
        val job = savePending()
        job.markSubmitted("existing-worker", Instant.now())
        repository.saveAndFlush(job)

        // client.submit이 호출되어도 상태 변경이 없어야 함
        submitter.runOnce()

        val updated = repository.findById(job.id).get()
        assertThat(updated.status).isEqualTo(JobStatus.IN_PROGRESS)
        assertThat(updated.workerJobId).isEqualTo("existing-worker")
    }

    @Test
    fun `워커가 동기적으로 FAILED 응답 시 즉시 FAILED 전이`() {
        val job = savePending()
        whenever(client.submit(any()))
            .thenReturn(WorkerJobSnapshot("w-1", WorkerJobStatus.FAILED, null))

        submitter.runOnce()

        assertThat(repository.findById(job.id).get().status).isEqualTo(JobStatus.FAILED)
    }
}
